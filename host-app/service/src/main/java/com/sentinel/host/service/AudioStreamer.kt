package com.sentinel.host.service

import android.os.SystemClock
import android.util.Log
import com.sentinel.host.data.audio.AudioPipeline
import com.sentinel.host.data.repository.AudioRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.sentinel.host.domain.session.CaptureOperation
import com.sentinel.host.domain.session.SessionManager
import java.util.UUID

/**
 * Orchestrates audio streaming tied to the connection lifecycle.
 *
 * Mirrors [LocationStreamer]'s lifecycle pattern:
 * - [start]  when ConnectionState becomes Ready
 * - [stop]   on disconnect or user stop
 * - [pause]  during reconnect — stops capture, preserves state
 * - [resume] after reconnect succeeds — restarts capture
 *
 * Permission handling:
 * - Caller must check RECORD_AUDIO permission before calling [start].
 * - If [hasPermission] is false, [start] is a no-op.
 *
 * The AudioStreamer does NOT know about ConnectionState.
 * The ConnectionSupervisor calls start/stop/pause/resume.
 *
 * Audio flow:
 * ```
 * AudioPipeline.frames → AudioRepositoryImpl.sendFrame() → WebSocket.sendBinary()
 * ```
 */
open class AudioStreamer(
    private val audioRepository: AudioRepositoryImpl,
    private val pipeline: AudioPipeline,
    private val scope: CoroutineScope,
    private val context: android.content.Context? = null,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val sessionManager: SessionManager? = null
) {

    companion object {
        private const val TAG = "Sentinel:AudioStream"
        val NIL_UUID = UUID(0L, 0L)
    }

    private var collectJob: Job? = null

    private val audioManager by lazy {
        context?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    }

    private var audioFocusRequest: Any? = null

    /** Tracks whether audio streaming was paused due to an incoming call or audio focus interruption. */
    @Volatile
    var isInterruptedByCall: Boolean = false
        private set

    val audioFocusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.w(TAG, "Audio focus lost transiently (call/alarm) — pausing audio capture gracefully")
                synchronized(lifecycleLock) {
                    if (isStreaming) {
                        isInterruptedByCall = true
                        pauseCaptureOnly()
                    }
                }
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus regained — resuming audio capture")
                synchronized(lifecycleLock) {
                    if (isInterruptedByCall) {
                        handleAudioFocusGain()
                    }
                }
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Audio focus lost permanently — stopping audio stream")
                synchronized(lifecycleLock) {
                    isInterruptedByCall = false
                    stop()
                }
            }
        }
    }

    /** Default maximum duration for an on-demand listening session (5 minutes). */
    val defaultMaxDurationSeconds: Long = 300L

    private var timeoutJob: Job? = null

    /** Monotonic deadline timestamp in elapsedRealtime milliseconds. */
    private var sessionDeadlineRealtimeMs: Long = 0L

    /** Monotonic generation counter to prevent delayed watchdogs from terminating newer sessions. */
    private var sessionGeneration: Long = 0L

    /** The active stream UUID for tagging frames in AUDIO_V2. */
    @Volatile
    var activeStreamId: UUID? = null
        private set

    /** Whether audio streaming is currently paused during reconnection. */
    @Volatile
    var isPaused: Boolean = false
        private set

    /** Callbacks invoked when audio streaming session starts, stops, or times out. */
    var onSessionStarted: (() -> Unit)? = null
    var onSessionStopped: (() -> Unit)? = null
    var onSessionTimeout: (() -> Unit)? = null

    /** Whether RECORD_AUDIO permission has been granted. Set by the UI/permission layer. */
    @Volatile
    var hasPermission: Boolean = false

    internal val lifecycleLock = Any()

    /** Test hook invoked by watchdog coroutines immediately before acquiring [lifecycleLock]. */
    internal var onWatchdogAboutToAcquireLock: (() -> Unit)? = null

    /** Test hook invoked by watchdog coroutines immediately after releasing [lifecycleLock] upon completion. */
    internal var onWatchdogFinished: (() -> Unit)? = null

    /** Whether audio is currently being captured and streamed. */
    val isStreaming: Boolean get() = collectJob?.isActive == true && pipeline.isRunning

    /**
     * Starts audio capture and begins streaming frames.
     * No-op if [hasPermission] is false.
     * If already streaming, preserves the existing deadline without resetting the timer.
     * Automatically stops and releases mic when session deadline expires.
     */
    fun start(
        maxDurationSeconds: Long = defaultMaxDurationSeconds,
        streamId: UUID? = null
    ): Unit = synchronized(lifecycleLock) {
        if (!hasPermission) {
            Log.w(TAG, "No RECORD_AUDIO permission — skipping start")
            return
        }

        if (sessionManager != null) {
            if (streamId == null || streamId == NIL_UUID) {
                Log.w(TAG, "Rejecting start audio: Missing or nil stream UUID")
                return
            }
            if (!sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM)) {
                Log.w(TAG, "Rejecting start audio: Permit not granted for stream $streamId")
                return
            }
        } else if (streamId == NIL_UUID) {
            Log.w(TAG, "Rejecting start audio: Nil stream UUID")
            return
        }

        if (isStreaming) {
            Log.i(TAG, "Audio streaming already active — preserving existing deadline")
            return
        }

        stopInternal(preserveDeadline = false) // Prevent lingering state
        isPaused = false

        requestAudioFocus()
        isInterruptedByCall = false

        val assignedStreamId = streamId ?: UUID.randomUUID()
        activeStreamId = assignedStreamId

        // Strictly clamp duration: never allow zero or negative durations to bypass the watchdog
        val durationSec = if (maxDurationSeconds <= 0L) {
            defaultMaxDurationSeconds
        } else {
            maxDurationSeconds.coerceIn(1L, 300L)
        }
        sessionDeadlineRealtimeMs = clock() + (durationSec * 1000L)
        val currentGen = ++sessionGeneration

        audioRepository.startCapture(assignedStreamId)

        collectJob = pipeline.frames
            .onEach { frame -> audioRepository.sendFrame(frame) }
            .launchIn(scope)

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            val remainingMs = sessionDeadlineRealtimeMs - clock()
            if (remainingMs > 0) {
                delay(remainingMs)
            }
            onWatchdogAboutToAcquireLock?.invoke()
            synchronized(lifecycleLock) {
                if ((isStreaming || isInterruptedByCall) && sessionGeneration == currentGen) {
                    Log.i(TAG, "Audio session (gen=$currentGen, streamId=$assignedStreamId) reached maximum duration ($durationSec s) — auto-stopping mic")
                    isInterruptedByCall = false
                    stop()
                    onSessionTimeout?.invoke()
                }
            }
            onWatchdogFinished?.invoke()
        }

        onSessionStarted?.invoke()
        Log.i(TAG, "Audio streaming started (gen=$currentGen, streamId=$assignedStreamId, maxDuration=${durationSec}s)")
    }

    /**
     * Stops audio capture and streaming, releases hardware resources.
     */
    fun stop(streamId: UUID? = null): Unit = synchronized(lifecycleLock) {
        if (streamId != null && activeStreamId != null && streamId != activeStreamId) {
            Log.w(TAG, "Ignoring stop request for mismatched streamId $streamId (active is $activeStreamId)")
            return
        }
        val targetStreamId = streamId ?: activeStreamId
        isPaused = false
        stopInternal(preserveDeadline = false)
        sessionManager?.invalidateOnStop(targetStreamId)
    }

    private fun stopInternal(preserveDeadline: Boolean = false) {
        if (!preserveDeadline) {
            sessionDeadlineRealtimeMs = 0L
            isPaused = false
            activeStreamId = null
        }
        sessionGeneration++ // Invalidate any waiting watchdog coroutines
        timeoutJob?.cancel()
        timeoutJob = null
        abandonAudioFocus()
        isInterruptedByCall = false
        collectJob?.cancel()
        collectJob = null
        audioRepository.stopCapture()
        onSessionStopped?.invoke()
        Log.i(TAG, "Audio streaming stopped")
    }

    /**
     * Pauses audio during reconnect.
     * Releases hardware resources during connection loss while preserving session deadline.
     */
    fun pause(): Unit = synchronized(lifecycleLock) {
        if (!isStreaming) {
            Log.w(TAG, "Cannot pause — not currently streaming")
            return
        }
        isPaused = true
        stopInternal(preserveDeadline = true)
        Log.i(TAG, "Audio streaming paused (reconnecting)")
    }

    /**
     * Resumes audio after reconnect.
     * Verifies valid paused session and remaining deadline, restoring watchdog.
     */
    fun resume(): Unit = synchronized(lifecycleLock) {
        if (!hasPermission) {
            Log.w(TAG, "No RECORD_AUDIO permission — skipping resume")
            return
        }

        if (!isPaused) {
            Log.w(TAG, "Cannot resume audio streaming — no paused session active")
            return
        }

        val streamId = activeStreamId
        if (sessionManager != null) {
            if (streamId == null || streamId == NIL_UUID || !sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM)) {
                Log.w(TAG, "Cannot resume audio streaming — permit invalid or expired for stream $streamId")
                stop()
                return
            }
        } else if (streamId == NIL_UUID) {
            Log.w(TAG, "Cannot resume audio streaming — nil active stream ID")
            stop()
            return
        }

        if (sessionDeadlineRealtimeMs <= 0L) {
            Log.w(TAG, "Cannot resume audio streaming — no active session deadline")
            isPaused = false
            return
        }

        val remainingMs = sessionDeadlineRealtimeMs - clock()
        if (remainingMs <= 0L) {
            Log.w(TAG, "Cannot resume audio streaming — session deadline already expired")
            stop()
            return
        }

        isPaused = false
        stopInternal(preserveDeadline = true) // Clean up lingering state

        requestAudioFocus()
        isInterruptedByCall = false

        val resumeGen = ++sessionGeneration

        audioRepository.startCapture(activeStreamId)

        collectJob = pipeline.frames
            .onEach { frame -> audioRepository.sendFrame(frame) }
            .launchIn(scope)

        // CRITICAL: Recompute remaining duration from the unchanged absolute deadline
        // AFTER cleanup and capture startup, immediately before scheduling the watchdog.
        // Time spent restarting capture must NOT extend the original deadline!
        val remainingAfterStartupMs = sessionDeadlineRealtimeMs - clock()
        if (remainingAfterStartupMs <= 0L) {
            Log.i(TAG, "Resumed audio session deadline reached during startup — auto-stopping mic")
            stop()
            onSessionTimeout?.invoke()
            return
        }

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            val remainingWatchdogMs = sessionDeadlineRealtimeMs - clock()
            if (remainingWatchdogMs > 0) {
                delay(remainingWatchdogMs)
            }
            onWatchdogAboutToAcquireLock?.invoke()
            synchronized(lifecycleLock) {
                if ((isStreaming || isInterruptedByCall) && sessionGeneration == resumeGen) {
                    Log.i(TAG, "Resumed audio session (gen=$resumeGen) expired — auto-stopping mic")
                    isInterruptedByCall = false
                    stop()
                    onSessionTimeout?.invoke()
                }
            }
            onWatchdogFinished?.invoke()
        }

        Log.i(TAG, "Audio streaming resumed (gen=$resumeGen, remaining=${remainingAfterStartupMs}ms)")
    }

    private fun pauseCaptureOnly() {
        collectJob?.cancel()
        collectJob = null
        audioRepository.stopCapture()
    }

    private fun handleAudioFocusGain() {
        if (!hasPermission) {
            isInterruptedByCall = false
            return
        }

        val streamId = activeStreamId
        if (sessionManager != null) {
            if (streamId == null || streamId == NIL_UUID || !sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM)) {
                Log.i(TAG, "Audio focus regained, but permit invalid or expired for stream $streamId — stopping session")
                isInterruptedByCall = false
                stop()
                return
            }
        } else if (streamId == NIL_UUID) {
            Log.i(TAG, "Audio focus regained, but nil active stream ID — stopping session")
            isInterruptedByCall = false
            stop()
            return
        }

        // Verify monotonic deadline before restarting capture
        val remainingMs = if (sessionDeadlineRealtimeMs > 0L) {
            sessionDeadlineRealtimeMs - clock()
        } else {
            0L
        }

        if (remainingMs <= 0L) {
            Log.i(TAG, "Audio focus regained, but session deadline already expired — stopping session")
            isInterruptedByCall = false
            stop()
            onSessionTimeout?.invoke()
            return
        }

        isInterruptedByCall = false
        val resumeGen = ++sessionGeneration

        pauseCaptureOnly()
        audioRepository.startCapture(activeStreamId)
        collectJob = pipeline.frames
            .onEach { frame -> audioRepository.sendFrame(frame) }
            .launchIn(scope)

        // CRITICAL: Recompute remaining duration from the unchanged absolute deadline
        // AFTER cleanup and capture startup, immediately before scheduling the watchdog.
        val remainingAfterStartupMs = sessionDeadlineRealtimeMs - clock()
        if (remainingAfterStartupMs <= 0L) {
            Log.i(TAG, "Audio session deadline reached during focus recovery startup — stopping session")
            stop()
            onSessionTimeout?.invoke()
            return
        }

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            val remainingWatchdogMs = sessionDeadlineRealtimeMs - clock()
            if (remainingWatchdogMs > 0) {
                delay(remainingWatchdogMs)
            }
            onWatchdogAboutToAcquireLock?.invoke()
            synchronized(lifecycleLock) {
                if ((isStreaming || isInterruptedByCall) && sessionGeneration == resumeGen) {
                    Log.i(TAG, "Resumed audio session after focus gain (gen=$resumeGen) expired — auto-stopping mic")
                    isInterruptedByCall = false
                    stop()
                    onSessionTimeout?.invoke()
                }
            }
            onWatchdogFinished?.invoke()
        }

        Log.i(TAG, "Audio streaming resumed after focus gain (gen=$resumeGen, remaining=${remainingAfterStartupMs}ms)")
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val playbackAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    android.media.AudioManager.STREAM_VOICE_CALL,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val req = audioFocusRequest as? android.media.AudioFocusRequest
                if (req != null) {
                    am.abandonAudioFocusRequest(req)
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
        }
    }
}
