package com.sentinel.admin.service

import android.util.Log
import com.sentinel.admin.data.remote.ReconnectPolicy
import com.sentinel.admin.data.remote.SequenceGenerator
import com.sentinel.admin.data.remote.protocol.MessageSerializer
import com.sentinel.admin.domain.model.ConnectionEvent
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.domain.supervisor.ConnectionSupervisor
import com.sentinel.admin.domain.repository.AuthRepository
import com.sentinel.admin.domain.repository.ConnectionRepository
import com.sentinel.admin.domain.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates the Admin connection lifecycle.
 *
 * Responsibilities:
 * - Owns the connection state machine
 * - Triggers authentication after WebSocket connects
 * - Starts heartbeat after authentication succeeds
 * - Handles reconnect with exponential backoff
 * - Stops heartbeat and audio on disconnect
 *
 * Does NOT hold a monolithic session object.
 * Connection state, selected device, and audio monitor are
 * separate concerns. This supervisor only coordinates them.
 *
 * State Machine:
 * Disconnected → Connecting → TransportConnected → Authenticating → Ready
 *                                                                     ↓
 *                                                  Reconnecting ← Error/Disconnect
 *
 * Sequence numbers are NOT reset on reconnect — they remain
 * monotonically increasing across the process lifetime.
 */
open class AdminSupervisor(
    private val connectionRepository: ConnectionRepository,
    private val authRepository: AuthRepository,
    private val heartbeatScheduler: HeartbeatScheduler,
    private val messageSerializer: MessageSerializer,
    private val sequenceGenerator: SequenceGenerator,
    private val reconnectPolicy: ReconnectPolicy,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val audioMonitor: AudioMonitor? = null,
    private val sessionPreferences: com.sentinel.admin.domain.session.SessionPreferences? = null
) : ConnectionSupervisor {

    companion object {
        private const val TAG = "Sentinel:AdminSupervisor"
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var eventCollectorJob: Job? = null
    private var heartbeatEventJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var serverUrl: String = ""

    @Volatile
    private var intentionalDisconnect = false

    /**
     * Connects to the server and starts the admin session.
     */
    override fun start(serverUrl: String) {
        this.serverUrl = serverUrl
        sessionPreferences?.let { it.serverUrl = serverUrl }
        intentionalDisconnect = false
        reconnectAttempt = 0

        startEventCollection()
        startHeartbeatEventCollection()
        connect()
    }

    /**
     * Ensures the supervisor is connected. If it was previously started
     * or has a saved serverUrl in sessionPreferences, reconnects automatically.
     */
    override fun ensureConnected() {
        val currentState = _connectionState.value
        if (currentState is ConnectionState.Ready ||
            currentState is ConnectionState.Connecting ||
            currentState is ConnectionState.Authenticating ||
            currentState is ConnectionState.TransportConnected) {
            return
        }

        val targetUrl = serverUrl.ifBlank { sessionPreferences?.serverUrl.orEmpty() }
        if (targetUrl.isNotBlank()) {
            Log.i(TAG, "ensureConnected: initiating connection to $targetUrl")
            start(targetUrl)
        } else {
            Log.w(TAG, "ensureConnected: no server URL available to connect")
        }
    }

    /**
     * Disconnects and stops all monitoring.
     */
    override fun stop() {
        Log.i(TAG, "Stopping supervisor")
        intentionalDisconnect = true

        reconnectJob?.cancel()
        reconnectJob = null

        heartbeatScheduler.stop()
        audioMonitor?.stop()
        eventCollectorJob?.cancel()
        heartbeatEventJob?.cancel()

        scope.launch {
            connectionRepository.disconnect()
        }

        _connectionState.value = ConnectionState.Disconnected
    }

    private fun connect() {
        if (connectionRepository.state.value is ConnectionState.TransportConnected) {
            Log.i(TAG, "Already connected, skipping connection state and starting authentication")
            reconnectAttempt = 0
            _connectionState.value = ConnectionState.Authenticating
            sendAuth()
            return
        }

        _connectionState.value = ConnectionState.Connecting
        scope.launch {
            connectionRepository.connect(serverUrl)
        }
    }

    private fun startEventCollection() {
        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch {
            connectionRepository.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun startHeartbeatEventCollection() {
        heartbeatEventJob?.cancel()
        heartbeatEventJob = scope.launch {
            heartbeatScheduler.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: ConnectionEvent) {
        Log.d(TAG, "Event: $event (state=${_connectionState.value})")

        when (event) {
            is ConnectionEvent.Connected -> {
                reconnectAttempt = 0
                _connectionState.value = ConnectionState.Authenticating
                sendAuth()
            }

            is ConnectionEvent.AuthResult -> {
                if (event.success) {
                    _connectionState.value = ConnectionState.Ready
                    heartbeatScheduler.reset()
                    heartbeatScheduler.start()
                    val streamIdToResume = audioMonitor?.activeStreamId?.toString()
                    audioMonitor?.resume()
                    audioMonitor?.activeDeviceId?.let { listeningTarget ->
                        Log.i(TAG, "Re-subscribing to audio listen for $listeningTarget after reconnect")
                        val seq = sequenceGenerator.next()
                        connectionRepository.sendText(
                            messageSerializer.serializeListen(
                                deviceId = listeningTarget,
                                sequence = seq,
                                streamId = streamIdToResume,
                                audioVersion = 2
                            )
                        )
                    }
                    Log.i(TAG, "Authenticated — ready")
                } else {
                    Log.e(TAG, "Authentication failed: ${event.error}")
                    _connectionState.value = ConnectionState.Error(
                        event.error ?: "Authentication failed"
                    )
                }
            }

            is ConnectionEvent.HeartbeatAck -> {
                heartbeatScheduler.onAckReceived()
            }

            is ConnectionEvent.Disconnected -> {
                heartbeatScheduler.stop()
                audioMonitor?.pause()
                if (!intentionalDisconnect) {
                    scheduleReconnect()
                }
            }

            is ConnectionEvent.ServerError -> {
                Log.w(TAG, "Server error: code=${event.code}, msg=${event.message}")
                if (event.code in 400..499) {
                    // Application-level error (e.g. 404 command target offline); do not teardown transport
                    return
                }
                heartbeatScheduler.stop()
                if (!intentionalDisconnect) {
                    scheduleReconnect()
                }
            }

            is ConnectionEvent.AudioFrameReceived -> {
                audioMonitor?.onBinaryFrame(event.data)
            }

            is ConnectionEvent.Reconnecting -> {
                _connectionState.value = ConnectionState.Reconnecting(event.attempt)
            }

            is ConnectionEvent.ReconnectFailed -> {
                Log.w(TAG, "Reconnect attempt ${event.attempt} failed: ${event.reason}")
            }

            is ConnectionEvent.ReconnectExhausted -> {
                _connectionState.value = ConnectionState.Error("Reconnect exhausted")
                Log.e(TAG, "All reconnect attempts exhausted")
            }

            // Device updates are handled directly by DeviceRepository
            // (not routed through supervisor)
            is ConnectionEvent.DeviceUpdateReceived -> { /* no-op */ }

            is ConnectionEvent.FilesListReceived -> { /* no-op, handled by FileViewModel */ }
            is ConnectionEvent.FileDownloadReceived -> { /* no-op, handled by FileDownloadManager */ }
            is ConnectionEvent.FileChunkReceived -> { /* no-op, handled by FileDownloadManager */ }
            is ConnectionEvent.CommandResultReceived -> { /* no-op, handled by DeviceRepository / ViewModels */ }
        }
    }

    private fun sendAuth() {
        val token = authRepository.getToken()
        if (token == null) {
            Log.e(TAG, "No auth token available")
            _connectionState.value = ConnectionState.Error("No auth token")
            return
        }

        val sequence = sequenceGenerator.next()
        val message = messageSerializer.serializeAuth(token, sequence)
        connectionRepository.sendText(message)
        Log.d(TAG, "AUTH sent (seq=$sequence)")
    }

    private fun scheduleReconnect() {
        if (!reconnectPolicy.shouldRetry(reconnectAttempt)) {
            _connectionState.value = ConnectionState.Error("Reconnect exhausted")
            Log.e(TAG, "All reconnect attempts exhausted (${reconnectPolicy.maxAttempts})")
            return
        }

        val delayMs = reconnectPolicy.getDelayMs(reconnectAttempt)
        val attempt = reconnectAttempt
        reconnectAttempt++

        _connectionState.value = ConnectionState.Reconnecting(attempt + 1)
        Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${attempt + 1}/${reconnectPolicy.maxAttempts})")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            // Do NOT reset sequence — keep monotonically increasing
            heartbeatScheduler.reset()
            connect()
        }
    }
}
