package com.sentinel.host.data.audio

import android.util.Log
import com.sentinel.host.domain.audio.AudioRecorder
import com.sentinel.host.domain.audio.OpusEncoder
import com.sentinel.host.domain.model.AudioFrame
import com.sentinel.shared.protocol.AudioConstants
import com.sentinel.shared.protocol.PacketType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single owner of the hot audio path.
 *
 * Responsibilities:
 * - Owns reusable PCM, Opus, and packet buffers (zero allocation in loop)
 * - Coordinates [AudioRecorder] and [OpusEncoder]
 * - Runs the capture→encode loop on a dedicated single-thread dispatcher
 * - Exposes encoded [AudioFrame]s via [frames] Flow
 * - Tracks statistics via [session]
 *
 * Architecture:
 * ```
 * Microphone → pcmBuffer → OpusEncoder → opusBuffer → AudioFrame → frames Flow
 *     ↑                                                                    ↓
 *  AudioRecorder                                               AudioRepository
 * ```
 *
 * Buffer design:
 * ```
 * pcmBuffer:  ShortArray(960)   — SAMPLES_PER_FRAME (20ms at 48kHz)
 * opusBuffer: ByteArray(4000)   — Max Opus frame (~4000 bytes)
 * ```
 * Both allocated once at [start], reused every 20ms cycle.
 *
 * Threading:
 * - Creates `Executors.newSingleThreadExecutor().asCoroutineDispatcher()` on start
 * - Closes the ExecutorCoroutineDispatcher on stop (no thread leak)
 * - Each start/stop cycle gets a fresh thread
 * - Single thread guarantees AudioRecorder and OpusEncoder thread safety
 *
 * Lifecycle:
 * - [start] → creates dispatcher, allocates buffers, starts recorder, begins capture loop
 * - [stop]  → cancels loop, stops recorder, closes encoder, closes dispatcher
 */
open class AudioPipeline(
    private val recorder: AudioRecorder,
    private val encoder: OpusEncoder,
    /**
     * Test-injectable dispatcher. When non-null, this dispatcher is used
     * instead of creating a new ExecutorCoroutineDispatcher. The pipeline
     * will NOT close a test-injected dispatcher.
     */
    private val testDispatcher: CoroutineDispatcher? = null
) {

    companion object {
        private const val TAG = "Sentinel:AudioPipe"

        /** Maximum Opus encoded frame size in bytes. */
        private const val MAX_OPUS_FRAME_SIZE = 4000

        /** Break capture loop after this many consecutive read errors. */
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** Bounded timeout for capture loop termination during teardown. */
        const val SHUTDOWN_TIMEOUT_MS = 1000L
    }

    private val _frames = MutableSharedFlow<AudioFrame>(
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Flow of encoded audio frames. Consumers collect this to send over WebSocket. */
    val frames: SharedFlow<AudioFrame> = _frames.asSharedFlow()

    /** Audio session statistics. */
    val session = AudioSession()

    /** Audio frame sequence number. Atomic for visibility across threads. */
    private val sequenceNumber = AtomicInteger(0)

    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var activeStreamId: UUID? = null

    /** Tracks whether the previous session failed to shut down cleanly within timeout. */
    @Volatile
    private var isCleanupFailed: Boolean = false

    /**
     * The owned ExecutorCoroutineDispatcher — created in [start], closed in [stop].
     * Null when using a test-injected dispatcher or when stopped.
     */
    private var ownedDispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher? = null

    /** Whether the pipeline is currently running. */
    val isRunning: Boolean get() = session.isActive

    private val lifecycleLock = Any()

    /**
     * Starts the audio capture → encode pipeline.
     *
     * 1. Creates a dedicated audio thread dispatcher
     * 2. Initializes the encoder (if NativeOpusEncoder)
     * 3. Starts the recorder
     * 4. Begins the capture loop on the audio dispatcher
     *
     * @param streamId Optional stream UUID for AUDIO_V2 29-byte packet tagging.
     * @return true if the pipeline started successfully.
     */
    fun start(streamId: UUID? = null): Boolean = synchronized(lifecycleLock) {
        if (isCleanupFailed || captureJob?.isActive == true) {
            Log.e(TAG, "Cannot start audio pipeline: previous capture session failed to terminate cleanly")
            return false
        }

        if (isRunning) {
            Log.w(TAG, "Already running — stopping first")
            try {
                stopInternal()
            } catch (t: Throwable) {
                Log.e(TAG, "Teardown of running session failed prior to start", t)
                return false
            }
            if (isCleanupFailed) {
                return false
            }
        }

        activeStreamId = streamId

        // Initialize encoder if it's a NativeOpusEncoder
        if (encoder is NativeOpusEncoder) {
            if (!encoder.initialize()) {
                Log.e(TAG, "Failed to initialize Opus encoder")
                return false
            }
        }

        // Start recording
        if (!recorder.start()) {
            Log.e(TAG, "Failed to start audio recorder")
            encoder.close()
            return false
        }

        // Create dispatcher — owned by this pipeline, closed on stop
        val dispatcher: CoroutineDispatcher = testDispatcher ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "sentinel-audio").apply {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            }
        }.asCoroutineDispatcher().also { ownedDispatcher = it }

        // Reset statistics and sequence
        session.start()
        sequenceNumber.set(0)

        // Start capture loop on dedicated audio thread
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        captureScope = scope
        captureJob = scope.launch { captureLoop() }

        Log.i(TAG, "Audio pipeline started")
        return true
    }

    /**
     * Stops the pipeline and releases all resources.
     * Closes the owned dispatcher to prevent thread leaks.
     */
    fun stop() = synchronized(lifecycleLock) {
        stopInternal()
    }

    private fun stopInternal() {
        val jobToWait = captureJob
        val scopeToCancel = captureScope

        var firstCleanupError: Throwable? = null

        // 1. Unblock reading: Call recorder.stop() first.
        // In Android, AudioRecord.read() in blocking mode blocks until samples are available or
        // until AudioRecord.stop() is invoked. Stopping the recorder unblocks any in-flight read().
        try {
            recorder.stop()
        } catch (t: Throwable) {
            firstCleanupError = t
            Log.e(TAG, "recorder.stop failed during teardown", t)
        }

        // 2. Cancel the capture coroutine scope so the loop breaks immediately upon read() return
        try {
            scopeToCancel?.cancel()
        } catch (t: Throwable) {
            if (firstCleanupError == null) firstCleanupError = t
            Log.e(TAG, "captureScope.cancel failed during teardown", t)
        }

        // 3. Await capture loop completion with a bounded timeout
        var workerConfirmedTerminated = (jobToWait == null)
        if (jobToWait != null) {
            try {
                runBlocking {
                    val completed = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                        jobToWait.join()
                        true
                    }
                    if (completed == true) {
                        workerConfirmedTerminated = true
                    } else {
                        Log.e(TAG, "Capture job did not complete within ${SHUTDOWN_TIMEOUT_MS}ms after recorder.stop()")
                    }
                }
            } catch (t: Throwable) {
                if (firstCleanupError == null) firstCleanupError = t
                Log.w(TAG, "captureJob join interrupted or failed: ${t.message}")
            }
        }

        if (!workerConfirmedTerminated) {
            // CRITICAL: The capture loop is STILL running or stuck in native HAL, or join threw an exception!
            // Do NOT close encoder or mark session stopped, as encoder may still be in use!
            // Preserve the unfinished session, block restart, and record explicit failure.
            isCleanupFailed = true
            val terminationError = IllegalStateException("Capture job failed to confirm termination within ${SHUTDOWN_TIMEOUT_MS}ms")
            if (firstCleanupError == null) firstCleanupError = terminationError
        } else {
            // Positively confirmed that capture loop has fully terminated:
            captureJob = null
            captureScope = null
            activeStreamId = null
            isCleanupFailed = false

            // 4. Safe to close encoder now that capture loop has terminated
            try {
                encoder.close()
            } catch (t: Throwable) {
                if (firstCleanupError == null) firstCleanupError = t
                Log.e(TAG, "encoder.close failed during teardown", t)
            }

            try {
                session.stop()
            } catch (t: Throwable) {
                if (firstCleanupError == null) firstCleanupError = t
                Log.e(TAG, "session.stop failed during teardown", t)
            }

            // Close owned dispatcher — releases the audio thread
            try {
                ownedDispatcher?.close()
            } catch (t: Throwable) {
                if (firstCleanupError == null) firstCleanupError = t
                Log.e(TAG, "ownedDispatcher.close failed during teardown", t)
            } finally {
                ownedDispatcher = null
            }

            Log.i(TAG, "Audio pipeline stopped (frames=${session.framesEncoded}, " +
                "drops=${session.droppedFrames}, avgEncode=${session.averageEncodeTimeUs}µs, " +
                "p99Encode=${session.encodeTimeP99Us}µs, " +
                "maxEncode=${session.maxEncodeTimeNs / 1000}µs)")
        }

        if (firstCleanupError != null) {
            throw firstCleanupError
        }
    }

    /**
     * The hot capture loop. Runs on the audio dispatcher.
     *
     * Zero allocations per cycle:
     * - pcmBuffer and opusBuffer are allocated once before the loop
     * - AudioFrame data is copied from opusBuffer (unavoidable for Flow emission)
     */
    private suspend fun captureLoop() {
        // Pre-allocate reusable buffers
        val pcmBuffer = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
        val opusBuffer = ByteArray(MAX_OPUS_FRAME_SIZE)

        Log.d(TAG, "Capture loop started (frameSize=${AudioConstants.SAMPLES_PER_FRAME}, " +
            "pcmBuffer=${pcmBuffer.size} shorts, opusBuffer=${opusBuffer.size} bytes)")

        val scope = captureScope ?: return

        var consecutiveErrors = 0

        while (scope.isActive) {
            // 1. Read PCM from microphone (blocking read — fills exactly SAMPLES_PER_FRAME)
            val samplesRead = recorder.read(pcmBuffer, 0, AudioConstants.SAMPLES_PER_FRAME)
            if (!scope.isActive) {
                break
            }
            if (samplesRead < 0) {
                Log.e(TAG, "AudioRecord.read() error: $samplesRead")
                session.recordDrop()
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.e(TAG, "Too many consecutive read errors — stopping capture")
                    break
                }
                continue
            }
            if (samplesRead < AudioConstants.SAMPLES_PER_FRAME) {
                // Partial read — shouldn't happen with blocking read, but handle it
                session.recordDrop()
                continue
            }
            consecutiveErrors = 0

            if (!scope.isActive) {
                break
            }

            // 2. Encode PCM → Opus
            val encodeStart = System.nanoTime()
            val encodedBytes = encoder.encode(
                pcmBuffer, AudioConstants.SAMPLES_PER_FRAME,
                opusBuffer, MAX_OPUS_FRAME_SIZE
            )
            val encodeTimeNs = System.nanoTime() - encodeStart

            if (!scope.isActive) {
                break
            }

            if (encodedBytes <= 0) {
                Log.w(TAG, "Opus encode failed: $encodedBytes")
                session.recordDrop()
                continue
            }

            // 3. Record statistics
            session.recordEncode(encodeTimeNs)

            // 4. Build AudioFrame (copy opus data — needed for safe Flow emission)
            val opusData = opusBuffer.copyOf(encodedBytes)
            val currentStreamId = activeStreamId
            val packetType = if (currentStreamId != null) PacketType.AUDIO_V2 else PacketType.AUDIO
            val frame = AudioFrame(
                packetType = packetType,
                sequence = sequenceNumber.getAndIncrement().toLong(),
                timestamp = System.currentTimeMillis(),
                opusData = opusData,
                streamId = currentStreamId
            )

            // 5. Emit frame (DROP_OLDEST if consumer is slow)
            _frames.tryEmit(frame)
        }

        Log.d(TAG, "Capture loop ended")
    }
}
