package com.sentinel.host.service

import android.util.Log
import com.sentinel.host.data.remote.ReconnectPolicy
import com.sentinel.host.domain.model.ConnectionEvent
import com.sentinel.host.domain.model.ConnectionState
import com.sentinel.host.domain.network.NetworkObserver
import com.sentinel.host.domain.repository.AuthRepository
import com.sentinel.host.domain.repository.ConnectionRepository
import com.sentinel.host.domain.repository.DeviceRepository
import com.sentinel.host.domain.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Single owner of connection lifecycle.
 *
 * Consumes [ConnectionEvent]s and derives business-level state.
 * Manages automatic reconnection with exponential backoff + jitter.
 * Manages heartbeat scheduling (start on Ready, stop on disconnect).
 * Manages location streaming (start on Ready, pause on reconnect).
 *
 * Architecture:
 *   Supervisor → Events → State
 *
 * Reconnect rules:
 * - Transport failures → reconnect with backoff
 * - Auth failures (401/403) → do NOT reconnect (bad credentials)
 * - User-initiated disconnect → do NOT reconnect
 * - Network lost → pause reconnect, resume on restoration
 * - Duplicate reconnects prevented via [reconnectJob] guard
 *
 * Heartbeat rules:
 * - Start when state becomes Ready
 * - Stop on disconnect, reconnect, or user stop
 * - Timeout triggers reconnect via error event
 *
 * Location rules:
 * - Start when state becomes Ready (if permission granted)
 * - Pause on disconnect/reconnect (saves battery)
 * - Resume automatically on Ready after reconnect
 *
 * Audio rules:
 * - Start when state becomes Ready (if RECORD_AUDIO granted)
 * - Pause on disconnect/reconnect (stops capture)
 * - Resume automatically on Ready after reconnect
 */
class ConnectionSupervisor(
    private val connectionRepository: ConnectionRepository,
    private val sessionManager: SessionManager,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val networkObserver: NetworkObserver,
    private val reconnectPolicy: ReconnectPolicy,
    private val heartbeatScheduler: HeartbeatScheduler,
    private val locationStreamer: LocationStreamer,
    private val audioStreamer: AudioStreamer,
    private val fileStreamer: FileStreamer,
    private val scope: CoroutineScope,
    private val connectTimeoutMs: Long = 15_000L,
    private val offlineBuffer: com.sentinel.host.data.location.OfflineTelemetryBuffer? = null
) {

    companion object {
        private const val TAG = "Sentinel:Supervisor"

        /** Error codes that indicate permanent auth failure — never retry. */
        private val AUTH_ERROR_CODES = setOf(401, 403)
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    private var observeJob: Job? = null
    private var reconnectJob: Job? = null
    private var heartbeatObserveJob: Job? = null
    private var transportStateObserveJob: Job? = null

    /** Callbacks for on-demand audio streaming session lifecycle. */
    var onStartAudioRequested: ((sequence: Long, deviceId: String, streamId: String?, maxDurationSeconds: Long?) -> Unit)? = null
    var onStopAudioRequested: ((sequence: Long, deviceId: String, streamId: String?) -> Unit)? = null

    /** True when the user explicitly called [stop]. Prevents auto-reconnect. */
    @Volatile
    private var userRequestedDisconnect = false

    /**
     * Starts observing connection events and network state.
     */
    fun start() {
        userRequestedDisconnect = false

        networkObserver.start()

        if (observeJob?.isActive != true) {
            observeJob = connectionRepository.events
                .onEach { event -> handleEvent(event) }
                .launchIn(scope)
        }

        // Observe heartbeat timeout events
        if (heartbeatObserveJob?.isActive != true) {
            heartbeatObserveJob = heartbeatScheduler.events
                .onEach { event -> handleEvent(event) }
                .launchIn(scope)
        }

        if (transportStateObserveJob?.isActive != true) {
            transportStateObserveJob = connectionRepository.state
                .onEach { transportState ->
                    if (!userRequestedDisconnect &&
                        _state.value is ConnectionState.Ready &&
                        (transportState is ConnectionState.Disconnected || transportState is ConnectionState.Error)
                    ) {
                        Log.w(TAG, "Zombie Ready state detected (transport is $transportState) — forcing immediate reconnect!")
                        audioStreamer.pause()
                        locationStreamer.pause()
                        heartbeatScheduler.stop()
                        startReconnectLoop(initialAttempt = 0, immediateFirst = true)
                    }
                }
                .launchIn(scope)
        }

        Log.i(TAG, "Supervisor started")

        if (!userRequestedDisconnect &&
            (_state.value is ConnectionState.Disconnected || connectionRepository.state.value is ConnectionState.Disconnected) &&
            reconnectJob?.isActive != true
        ) {
            startReconnectLoop(initialAttempt = 0, immediateFirst = true)
        }
    }

    /**
     * Stops the supervisor. Cancels reconnect and resets state.
     * User-initiated — suppresses auto-reconnect.
     */
    fun stop() {
        userRequestedDisconnect = true
        audioStreamer.stop()
        locationStreamer.stop()
        heartbeatScheduler.stop()
        cancelReconnect()
        heartbeatObserveJob?.cancel()
        heartbeatObserveJob = null
        observeJob?.cancel()
        observeJob = null
        transportStateObserveJob?.cancel()
        transportStateObserveJob = null
        networkObserver.stop()
        _state.value = ConnectionState.Disconnected
        Log.i(TAG, "Supervisor stopped (user-initiated)")
    }

    /**
     * Event-driven state machine.
     * Each event deterministically transitions to the next state.
     */
    private fun handleEvent(event: ConnectionEvent) {
        val previousState = _state.value

        val nextState = when (event) {
            is ConnectionEvent.Connected -> {
                cancelReconnect()
                Log.i(TAG, "WebSocket connected → Authenticating")
                ConnectionState.Authenticating
            }

            is ConnectionEvent.Authenticated -> {
                Log.i(TAG, "Authenticated → Registering")
                ConnectionState.Registering
            }

            is ConnectionEvent.Registered -> {
                onRegistered()
                ConnectionState.Ready
            }

            is ConnectionEvent.FilesListReq -> {
                fileStreamer.handleFilesListReq(event.path, event.sequence)
                previousState
            }

            is ConnectionEvent.FileDownloadReq -> {
                fileStreamer.handleFileDownloadReq(event.path, event.offset, event.nonce, event.sequence)
                previousState
            }

            is ConnectionEvent.FileChunkAck -> {
                fileStreamer.handleChunkAck(event.ackSequence)
                previousState
            }

            is ConnectionEvent.FileStopReq -> {
                fileStreamer.handleFileStopReq(event.path)
                previousState
            }

            is ConnectionEvent.StartAudio -> {
                Log.i(TAG, "StartAudio event received from server (seq=${event.sequence}, dev=${event.deviceId}, streamId=${event.streamId})")
                onStartAudioRequested?.invoke(event.sequence, event.deviceId, event.streamId, event.maxDurationSeconds)
                previousState
            }

            is ConnectionEvent.StopAudio -> {
                Log.i(TAG, "StopAudio event received from server (seq=${event.sequence}, dev=${event.deviceId}, streamId=${event.streamId})")
                onStopAudioRequested?.invoke(event.sequence, event.deviceId, event.streamId)
                previousState
            }

            is ConnectionEvent.HeartbeatAck -> {
                heartbeatScheduler.onAckReceived()
                previousState
            }

            is ConnectionEvent.Error -> {
                Log.e(TAG, "Error (code=${event.code}): ${event.message}")
                
                if (event.code == 409) {
                    Log.i(TAG, "Device already registered, moving to Ready")
                    onRegistered()
                    ConnectionState.Ready
                } else {
                    audioStreamer.pause()
                    locationStreamer.pause()
                    heartbeatScheduler.stop()
                    
                    if (isAuthError(event.code)) {
                        // Permanent failure — don't reconnect
                        ConnectionState.Error(event.message)
                    } else if (!userRequestedDisconnect && previousState != ConnectionState.Disconnected) {
                        // Transport failure — reconnect
                        startReconnectLoop()
                        ConnectionState.Reconnecting(1)
                    } else {
                        ConnectionState.Error(event.message)
                    }
                }
            }

            is ConnectionEvent.Disconnected -> {
                audioStreamer.pause()
                locationStreamer.pause()
                heartbeatScheduler.stop()
                if (!userRequestedDisconnect && previousState is ConnectionState.Ready) {
                    // Unexpected disconnect from Ready state — reconnect
                    Log.i(TAG, "Unexpected disconnect → reconnecting")
                    startReconnectLoop()
                    ConnectionState.Reconnecting(1)
                } else if (!userRequestedDisconnect && previousState is ConnectionState.Reconnecting) {
                    // Already reconnecting — stay in that state
                    previousState
                } else {
                    Log.i(TAG, "Disconnected")
                    ConnectionState.Disconnected
                }
            }

            // Reconnect events — surface to UI but state is managed by reconnect loop
            is ConnectionEvent.Reconnecting -> {
                _events.tryEmit(event)
                ConnectionState.Reconnecting(event.attempt)
            }

            is ConnectionEvent.ReconnectFailed -> {
                _events.tryEmit(event)
                previousState // Stay in Reconnecting
            }

            is ConnectionEvent.ReconnectExhausted -> {
                _events.tryEmit(event)
                ConnectionState.Error("Reconnect failed after ${reconnectPolicy.maxAttempts} attempts")
            }
        }

        if (nextState != previousState) {
            Log.i(TAG, "State: $previousState → $nextState")
            _state.value = nextState
        }
    }

    // ================================================================
    // Reconnect loop
    // ================================================================

    /**
     * Forces immediate reconnection without backoff delay.
     * Essential for FCM push-to-wake and watchdog revival.
     */
    fun forceReconnect() {
        Log.i(TAG, "Force reconnect requested (FCM wake / watchdog punch)")
        userRequestedDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        startReconnectLoop(initialAttempt = 0, immediateFirst = true)
    }

    /**
     * Starts the reconnect loop with exponential backoff.
     * Guards against duplicate concurrent attempts.
     */
    private fun startReconnectLoop(initialAttempt: Int = 0, immediateFirst: Boolean = false) {
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "Reconnect already in progress — skipping")
            return
        }

        reconnectJob = scope.launch {
            var attempt = initialAttempt

            while (reconnectPolicy.shouldRetry(attempt) && isActive) {
                val delayMs = if (immediateFirst && attempt == 0) 0L else reconnectPolicy.getDelayMs(attempt)

                Log.i(TAG, "Reconnect attempt ${attempt + 1} in ${delayMs}ms")
                _state.value = ConnectionState.Reconnecting(attempt + 1)

                if (delayMs > 0) {
                    delay(delayMs)
                }

                // Wait for network if unavailable
                if (!networkObserver.isAvailable.value) {
                    Log.i(TAG, "Waiting for network...")
                    networkObserver.isAvailable.first { it }
                    Log.i(TAG, "Network restored — attempting reconnect")
                }

                val success = attemptReconnect()
                if (success) {
                    Log.i(TAG, "Reconnect successful on attempt ${attempt + 1}")
                    return@launch
                }

                attempt++

                if (!isActive) return@launch
            }

            // Exhausted retries
            if (isActive) {
                Log.e(TAG, "Reconnect exhausted after $attempt attempts")
                _state.value = ConnectionState.Error(
                    "Reconnect failed after $attempt attempts"
                )
            }
        }
    }

    /**
     * Single reconnect attempt: connect → auth → register.
     * Returns true if the full pipeline succeeded.
     */
    private suspend fun attemptReconnect(): Boolean {
        val serverUrl = sessionManager.getServerUrl()
        val token = sessionManager.getToken()

        if (serverUrl == null || token == null) {
            Log.e(TAG, "No saved session — cannot reconnect")
            return false
        }

        return try {
            // Connect WebSocket
            connectionRepository.connect(serverUrl)

            withTimeout(connectTimeoutMs) {
                connectionRepository.events.first { it is ConnectionEvent.Connected }
            }

            // Resolve device info first (resets cloned token if hardware mismatch detected)
            val deviceInfo = deviceRepository.getDeviceInfo()
            val activeToken = sessionManager.getToken() ?: token

            // Authenticate
            val authResult = authRepository.authenticate(activeToken)
            if (authResult.isFailure) {
                val error = authResult.exceptionOrNull()
                Log.e(TAG, "Reconnect auth failed: ${error?.message}")

                // If auth error is permanent, abort all reconnection
                if (error is com.sentinel.host.data.repository.AuthenticationException
                    && isAuthError(error.code)
                ) {
                    cancelReconnect()
                    _state.value = ConnectionState.Error(error.message)
                    return false
                }
                return false
            }

            // Register
            val registerResult = deviceRepository.register(deviceInfo)
            if (registerResult.isFailure) {
                Log.e(TAG, "Reconnect register failed: ${registerResult.exceptionOrNull()?.message}")
                return false
            }

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reconnect attempt failed: ${e.message}")
            false
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun isAuthError(code: Int): Boolean = code in AUTH_ERROR_CODES

    private fun onRegistered() {
        Log.i(TAG, "Registered/Ready status confirmed")
        heartbeatScheduler.reset()
        heartbeatScheduler.start()
        locationStreamer.start()
        // Audio capture is purely on-demand to protect privacy indicator and battery

        // Flush offline Room SQLite telemetry buffer
        scope.launch {
            try {
                val flushed = offlineBuffer?.flushBuffer { jsonPayload ->
                    connectionRepository.sendText(jsonPayload)
                } ?: 0
                if (flushed > 0) {
                    Log.i(TAG, "Successfully synced $flushed offline GPS fixes upon reconnection")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing offline telemetry buffer: ${e.message}")
            }
        }
    }
}
