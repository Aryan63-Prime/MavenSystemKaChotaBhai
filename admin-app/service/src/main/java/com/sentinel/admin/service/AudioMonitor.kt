package com.sentinel.admin.service

import android.util.Log
import com.sentinel.admin.data.audio.AudioFrameParser
import com.sentinel.admin.data.audio.AudioOutput
import com.sentinel.admin.data.audio.JitterBuffer
import com.sentinel.admin.data.audio.NativeOpusDecoder
import com.sentinel.admin.domain.model.AudioStatistics
import com.sentinel.admin.domain.model.PlaybackState
import com.sentinel.shared.protocol.AudioConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates the audio playback pipeline.
 *
 * Pipeline: Binary frame → Parse → Jitter Buffer → Decode (or PLC) → AudioOutput
 *
 * Owns [PlaybackState] and [AudioStatistics] — not the ViewModel.
 * The ViewModel observes these StateFlows.
 *
 * Production invariants:
 * - Never blocks the playback thread.
 * - Never allocates in the hot loop except TreeMap removal (unavoidable).
 * - Never recreates decoder for a bad packet — drops frame, continues.
 * - Decoder failures affect only the current frame.
 * - All JNI resources released exactly once via [stop].
 * - All methods idempotent: start(), pause(), resume(), stop().
 * - Recovers after reconnect without full recreation.
 *
 * Latency budget:
 * - Jitter buffer: 40ms (2 frames)
 * - Opus decode: <1ms
 * - AudioTrack: 10-20ms
 * - Target end-to-end: <100ms
 */
class AudioMonitor(
    private val decoder: NativeOpusDecoder,
    private val audioOutput: AudioOutput,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "Sentinel:AudioMonitor"
        private const val PLAYBACK_LOOP_INTERVAL_MS = 10L
    }

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _statistics = MutableStateFlow(AudioStatistics())
    val statistics: StateFlow<AudioStatistics> = _statistics.asStateFlow()

    private val _incomingAudioLevel = MutableStateFlow(0f)
    val incomingAudioLevel: StateFlow<Float> = _incomingAudioLevel.asStateFlow()

    private val _incomingAudioDbFs = MutableStateFlow(-60)
    val incomingAudioDbFs: StateFlow<Int> = _incomingAudioDbFs.asStateFlow()

    private val _incomingAudioFreqHz = MutableStateFlow(0)
    val incomingAudioFreqHz: StateFlow<Int> = _incomingAudioFreqHz.asStateFlow()

    private val _incomingAcousticClass = MutableStateFlow("QUIET ROOM")
    val incomingAcousticClass: StateFlow<String> = _incomingAcousticClass.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    @Volatile
    private var wavWriter: com.sentinel.admin.data.audio.WavFileWriter? = null

    @Volatile
    private var recordingStartTimeMs: Long = 0L

    private val jitterBuffer = JitterBuffer()

    // Pre-allocated buffers — no allocation in hot loop
    private val pcmBuffer = ShortArray(AudioConstants.SAMPLES_PER_FRAME)

    private var playbackJob: Job? = null

    @Volatile
    var activeDeviceId: String? = null
        private set

    @Volatile
    var activeStreamId: java.util.UUID? = null
        private set

    // Mutable stats counters (updated atomically via _statistics.value = ...)
    private var framesReceived = 0L
    private var framesDecoded = 0L
    private var framesDropped = 0L
    private var decoderFailures = 0L
    private var plcFrames = 0L

    /**
     * Starts writing incoming decoded PCM audio to the specified target WAV file.
     */
    @Synchronized
    fun startRecording(targetFile: java.io.File): Boolean {
        if (_isRecording.value) {
            Log.w(TAG, "Recording already active")
            return true
        }

        try {
            val writer = com.sentinel.admin.data.audio.WavFileWriter(targetFile)
            wavWriter = writer
            recordingStartTimeMs = System.currentTimeMillis()
            _recordingDurationMs.value = 0L
            _isRecording.value = true
            Log.i(TAG, "Started recording live stream to ${targetFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            return false
        }
    }

    /**
     * Stops current recording and returns the finalized WAV file.
     */
    @Synchronized
    fun stopRecording(): java.io.File? {
        val writer = wavWriter ?: return null
        wavWriter = null
        _isRecording.value = false
        _recordingDurationMs.value = 0L

        val savedFile = writer.close()
        Log.i(TAG, "Stopped recording. Saved file: ${savedFile?.absolutePath}")
        return savedFile
    }

    // ============================================================
    // Lifecycle (all idempotent)
    // ============================================================

    /**
     * Starts the audio pipeline for the given device.
     * Idempotent — if already started for the same device and streamId, no-op.
     * If started for a different device or stream, stops first then starts.
     */
    fun start(deviceId: String, streamId: java.util.UUID? = null) {
        if (activeDeviceId == deviceId && (streamId == null || activeStreamId == streamId) && playbackJob?.isActive == true) {
            Log.d(TAG, "Already listening to $deviceId")
            return
        }

        if (activeDeviceId != null) {
            stopInternal()
        }

        activeDeviceId = deviceId
        activeStreamId = streamId
        resetCounters()
        jitterBuffer.reset()

        if (!decoder.isInitialized()) {
            if (!decoder.initialize()) {
                _playbackState.value = PlaybackState.Error("Failed to initialize decoder")
                return
            }
        }

        if (!audioOutput.initialize()) {
            _playbackState.value = PlaybackState.Error("Failed to initialize audio output")
            return
        }

        _playbackState.value = PlaybackState.Connecting
        Log.i(TAG, "Audio pipeline started for $deviceId")

        startPlaybackLoop()
    }

    /**
     * Pauses playback (e.g., WebSocket disconnected).
     * The pipeline stays alive — resume() continues without recreation.
     * Idempotent.
     */
    fun pause() {
        val current = _playbackState.value
        if (current == PlaybackState.Playing || current == PlaybackState.Buffering ||
            current == PlaybackState.Connecting) {
            audioOutput.pause()
            _playbackState.value = PlaybackState.Paused
            _incomingAudioLevel.value = 0f
            _incomingAudioDbFs.value = -60
            _incomingAudioFreqHz.value = 0
            _incomingAcousticClass.value = "QUIET ROOM"
            Log.i(TAG, "Audio paused")
        }
    }

    /**
     * Resumes playback after pause (e.g., WebSocket reconnected).
     * Does NOT recreate decoder or AudioOutput — just resumes.
     * Idempotent.
     */
    fun resume() {
        if (_playbackState.value == PlaybackState.Paused) {
            audioOutput.resume()
            jitterBuffer.clear() // Clear stale frames from before disconnect
            activeStreamId = null
            _playbackState.value = PlaybackState.Buffering
            Log.i(TAG, "Audio resumed — rebuffering")
        }
    }

    /**
     * Stops the entire pipeline and releases all resources.
     * Idempotent.
     */
    fun stop() {
        stopInternal()
        _playbackState.value = PlaybackState.Stopped
    }

    // ============================================================
    // Frame ingestion (called from WebSocket event collector on IO)
    // ============================================================

    /**
     * Receives a binary frame from the WebSocket.
     * Called on IO dispatcher — never on Main.
     * Never blocks. Never throws.
     */
    fun onBinaryFrame(data: ByteArray) {
        if (activeDeviceId == null) return

        val frame = AudioFrameParser.parse(data) ?: run {
            framesDropped++
            updateStatistics()
            return
        }

        // Validate stream UUID for AUDIO_V2 frames
        val expectedStream = activeStreamId
        if (frame.streamId != null) {
            if (expectedStream != null && frame.streamId != expectedStream) {
                // Reject stale frame from mismatched stream session
                framesDropped++
                updateStatistics()
                return
            } else if (expectedStream == null) {
                activeStreamId = frame.streamId
            }
        }

        framesReceived++

        when (jitterBuffer.push(frame.sequence, frame.payload)) {
            JitterBuffer.PushResult.ACCEPTED -> {
                // Transition to Buffering when first frame arrives
                if (_playbackState.value == PlaybackState.Connecting) {
                    _playbackState.value = PlaybackState.Buffering
                }
            }
            JitterBuffer.PushResult.DUPLICATE -> {
                // Stats tracked inside JitterBuffer
            }
            JitterBuffer.PushResult.LATE -> {
                framesDropped++
            }
        }

        updateStatistics()
    }

    // ============================================================
    // Playback loop (runs on Dispatchers.IO)
    // ============================================================

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Playback loop started")

            while (isActive) {
                val state = _playbackState.value

                when (state) {
                    is PlaybackState.Buffering -> {
                        if (jitterBuffer.isReady()) {
                            _playbackState.value = PlaybackState.Playing
                            Log.i(TAG, "Buffer ready — playing")
                        }
                    }
                    is PlaybackState.Playing -> {
                        drainBuffer()
                    }
                    is PlaybackState.Paused,
                    is PlaybackState.Connecting -> {
                        // Wait for frames/resume
                    }
                    else -> {
                        // Stopped, Error, Idle — exit loop
                        break
                    }
                }

                delay(PLAYBACK_LOOP_INTERVAL_MS)
            }

            Log.d(TAG, "Playback loop exited")
        }
    }

    /**
     * Drains the jitter buffer, decoding and playing frames.
     * Uses pre-allocated pcmBuffer — no allocations.
     * Decoder failures drop the frame, never destroy the decoder.
     */
    private fun drainBuffer() {
        // Process up to 3 frames per loop iteration to avoid falling behind
        repeat(3) {
            when (val result = jitterBuffer.pop()) {
                is JitterBuffer.PopResult.Frame -> {
                    val decoded = decoder.decode(
                        result.payload,
                        result.payload.size,
                        pcmBuffer,
                        AudioConstants.SAMPLES_PER_FRAME
                    )

                    if (decoded > 0) {
                        audioOutput.write(pcmBuffer, 0, decoded)
                        wavWriter?.writePcm(pcmBuffer, decoded)
                        if (_isRecording.value) {
                            _recordingDurationMs.value = System.currentTimeMillis() - recordingStartTimeMs
                        }
                        framesDecoded++

                        var sumSq = 0.0
                        var zeroCrossings = 0
                        var peak = 0.0
                        var lag1Cross = 0.0
                        for (i in 0 until decoded) {
                            val s = pcmBuffer[i].toInt()
                            val norm = s / 32768.0
                            val absNorm = kotlin.math.abs(norm)
                            if (absNorm > peak) peak = absNorm
                            sumSq += norm * norm
                            if (i > 0) {
                                val prev = pcmBuffer[i - 1].toInt()
                                val prevNorm = prev / 32768.0
                                lag1Cross += norm * prevNorm
                                if ((s >= 0 && prev < 0) || (s < 0 && prev >= 0)) {
                                    zeroCrossings++
                                }
                            }
                        }
                        val rms = kotlin.math.sqrt(sumSq / decoded).toFloat()
                        val dbFs = if (rms > 0.0001f) (20.0 * kotlin.math.log10(rms.toDouble())).toInt().coerceIn(-60, 0) else -60
                        val freqHz = if (dbFs > -50) ((zeroCrossings * 16000) / (2 * decoded.coerceAtLeast(1))) else 0
                        val crestFactor = if (rms > 0.0001f) (peak / rms) else 1.0
                        val voicingCorr = if (sumSq > 0.000001) (lag1Cross / sumSq) else 0.0
                        val acousticClass = when {
                            dbFs <= -46 -> "QUIET ROOM"
                            crestFactor > 5.2 && dbFs > -22 -> "SHARP IMPULSE / KNOCK"
                            dbFs > -12 -> "LOUD / HIGH SPL"
                            voicingCorr > 0.68 && freqHz in 85..450 && dbFs > -42 -> "HUMAN SPEECH (VOICED)"
                            freqHz in 1..109 && dbFs > -38 -> "LOW-FREQ HUM / ENGINE"
                            else -> "AMBIENT SOUND"
                        }
                        _incomingAudioLevel.value = (rms * 3.5f).coerceIn(0f, 1f)
                        _incomingAudioDbFs.value = dbFs
                        _incomingAudioFreqHz.value = freqHz
                        _incomingAcousticClass.value = acousticClass
                    } else {
                        // Decoder failure — drop frame, continue. Never recreate decoder.
                        decoderFailures++
                        Log.w(TAG, "Decode failed for seq=${result.sequence}, continuing")
                    }
                    updateStatistics()
                }
                is JitterBuffer.PopResult.Missing -> {
                    // Packet loss concealment
                    val plcSamples = decoder.decodePLC(
                        pcmBuffer,
                        AudioConstants.SAMPLES_PER_FRAME
                    )

                    if (plcSamples > 0) {
                        audioOutput.write(pcmBuffer, 0, plcSamples)
                        plcFrames++
                    }
                    updateStatistics()
                }
                is JitterBuffer.PopResult.Empty -> {
                    // Buffer underrun — might go back to buffering if persistent
                    return
                }
            }
        }
    }

    // ============================================================
    // Internal
    // ============================================================

    private fun stopInternal() {
        activeDeviceId = null
        activeStreamId = null
        playbackJob?.cancel()
        playbackJob = null
        audioOutput.release()
        decoder.close()
        jitterBuffer.clear()
        _incomingAudioLevel.value = 0f
        _incomingAudioDbFs.value = -60
        _incomingAudioFreqHz.value = 0
        _incomingAcousticClass.value = "QUIET ROOM"
        Log.i(TAG, "Audio pipeline stopped")
    }

    private fun resetCounters() {
        framesReceived = 0
        framesDecoded = 0
        framesDropped = 0
        decoderFailures = 0
        plcFrames = 0
    }

    private fun updateStatistics() {
        _statistics.value = AudioStatistics(
            framesReceived = framesReceived,
            framesDecoded = framesDecoded,
            framesDropped = framesDropped,
            latePackets = jitterBuffer.droppedLate,
            duplicatePackets = jitterBuffer.droppedDuplicate,
            decoderFailures = decoderFailures,
            plcFrames = plcFrames,
            currentLatencyMs = jitterBuffer.size().toLong() * AudioConstants.FRAME_DURATION_MS
        )
    }
}
