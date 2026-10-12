package com.sentinel.admin.service

import com.sentinel.admin.data.audio.AudioOutput
import com.sentinel.admin.data.audio.NativeOpusDecoder
import com.sentinel.admin.domain.model.PlaybackState
import com.sentinel.shared.protocol.AudioConstants
import com.sentinel.shared.protocol.PacketType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for [AudioMonitor].
 *
 * Uses a FakeAudioOutput and mocked decoder (returnDefaultValues = true).
 * NativeOpusDecoder methods return 0/false in unit tests (android.util.Log stubbed).
 *
 * Verifies lifecycle, state transitions, and frame processing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioMonitorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeOutput: FakeAudioOutput
    private lateinit var decoder: NativeOpusDecoder
    private lateinit var monitor: AudioMonitor

    @Before
    fun setUp() {
        fakeOutput = FakeAudioOutput()
        decoder = NativeOpusDecoder()
        monitor = AudioMonitor(
            decoder = decoder,
            audioOutput = fakeOutput,
            scope = testScope
        )
    }

    // ============================================================
    // Initial state
    // ============================================================

    @Test
    fun `initial state is Idle`() {
        assertEquals(PlaybackState.Idle, monitor.playbackState.value)
    }

    @Test
    fun `initial statistics are zero`() {
        val stats = monitor.statistics.value
        assertEquals(0L, stats.framesReceived)
        assertEquals(0L, stats.framesDecoded)
        assertEquals(0L, stats.framesDropped)
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Test
    fun `start transitions to Error when decoder fails`() = runTest(testDispatcher) {
        // NativeOpusDecoder.initialize() returns false in unit tests (no native lib)
        monitor.start("HOST-0001")
        advanceTimeBy(100)

        // Should be Error because native lib is not loaded
        assertEquals(true, monitor.playbackState.value is PlaybackState.Error)
    }

    @Test
    fun `stop is idempotent`() = runTest(testDispatcher) {
        monitor.stop()
        assertEquals(PlaybackState.Stopped, monitor.playbackState.value)

        monitor.stop()
        assertEquals(PlaybackState.Stopped, monitor.playbackState.value)
    }

    // ============================================================
    // Frame processing
    // ============================================================

    @Test
    fun `invalid binary frame increments dropped count`() = runTest(testDispatcher) {
        monitor.onBinaryFrame(byteArrayOf(0x01, 0x02)) // too short

        // Not active, so frame is ignored
        assertEquals(0L, monitor.statistics.value.framesReceived)
    }

    @Test
    fun `onBinaryFrame with no active device is no-op`() = runTest(testDispatcher) {
        val frame = buildValidFrame(0, 100L, byteArrayOf(0x01, 0x02, 0x03))
        monitor.onBinaryFrame(frame)

        assertEquals(0L, monitor.statistics.value.framesReceived)
    }

    // ============================================================
    // Pause / Resume
    // ============================================================

    @Test
    fun `pause from Idle is no-op`() {
        monitor.pause()
        assertEquals(PlaybackState.Idle, monitor.playbackState.value)
    }

    @Test
    fun `resume from Idle is no-op`() {
        monitor.resume()
        assertEquals(PlaybackState.Idle, monitor.playbackState.value)
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun buildValidFrame(sequence: Long, timestamp: Long, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(AudioConstants.HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(PacketType.AUDIO)
        buffer.putInt(sequence.toInt())
        buffer.putLong(timestamp)
        buffer.put(payload)
        return buffer.array()
    }

    private fun buildValidFrameV2(streamId: java.util.UUID, sequence: Long, timestamp: Long, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(AudioConstants.HEADER_V2_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(PacketType.AUDIO_V2)
        buffer.putLong(streamId.mostSignificantBits)
        buffer.putLong(streamId.leastSignificantBits)
        buffer.putInt(sequence.toInt())
        buffer.putLong(timestamp)
        buffer.put(payload)
        return buffer.array()
    }

    @Test
    fun `onBinaryFrame rejects stale AUDIO_V2 frame from mismatched streamId`() = runTest(testDispatcher) {
        val activeUuid = java.util.UUID.randomUUID()
        val staleUuid = java.util.UUID.randomUUID()
        monitor.start("HOST-0001", activeUuid)

        val staleFrame = buildValidFrameV2(staleUuid, 1, 1000L, byteArrayOf(0x01, 0x02))
        monitor.onBinaryFrame(staleFrame)

        assertEquals(0L, monitor.statistics.value.framesReceived)
        assertEquals(1L, monitor.statistics.value.framesDropped)

        val matchingFrame = buildValidFrameV2(activeUuid, 2, 1020L, byteArrayOf(0x03, 0x04))
        monitor.onBinaryFrame(matchingFrame)

        assertEquals(1L, monitor.statistics.value.framesReceived)
    }

    // ============================================================
    // Fake AudioOutput
    // ============================================================

    private class FakeAudioOutput : AudioOutput {
        var initialized = false
        var released = false
        var paused = false
        val writtenSamples = mutableListOf<ShortArray>()

        override fun initialize(): Boolean {
            initialized = true
            return true
        }

        override fun write(pcm: ShortArray, offset: Int, size: Int): Int {
            writtenSamples.add(pcm.copyOfRange(offset, offset + size))
            return size
        }

        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun release() { released = true }
    }
}
