package com.sentinel.host.data.repository

import com.sentinel.host.data.audio.AudioPipeline
import com.sentinel.host.domain.audio.AudioRecorder
import com.sentinel.host.domain.audio.OpusEncoder
import com.sentinel.host.domain.model.AudioFrame
import com.sentinel.host.domain.model.ConnectionEvent
import com.sentinel.host.domain.model.ConnectionState
import com.sentinel.host.domain.repository.ConnectionRepository
import com.sentinel.shared.protocol.AudioConstants
import com.sentinel.shared.protocol.PacketType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRepositoryImplTest {

    private lateinit var fakeConnection: RecordingConnectionRepository
    private lateinit var repository: AudioRepositoryImpl
    private lateinit var pipeline: AudioPipeline

    @Before
    fun setUp() {
        fakeConnection = RecordingConnectionRepository()
        pipeline = AudioPipeline(
            recorder = StubAudioRecorder(),
            encoder = StubOpusEncoder(),
            testDispatcher = Dispatchers.Unconfined
        )
        repository = AudioRepositoryImpl(pipeline, fakeConnection)
    }

    // ================================================================
    // sendFrame — binary correctness
    // ================================================================

    @Test
    fun `sendFrame builds binary packet and calls sendBinary`() {
        val opusData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val frame = AudioFrame(PacketType.AUDIO, 42, 1234567890L, opusData)

        val result = repository.sendFrame(frame)

        assertTrue(result)
        assertEquals(1, fakeConnection.binarySent.size)

        // Verify binary layout
        val packet = fakeConnection.binarySent[0]
        assertEquals(AudioConstants.HEADER_SIZE + opusData.size, packet.size)

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        assertEquals(PacketType.AUDIO, buf.get())
        assertEquals(42, buf.getInt())
        assertEquals(1234567890L, buf.getLong())

        val extractedOpus = ByteArray(opusData.size)
        buf.get(extractedOpus)
        assertArrayEquals(opusData, extractedOpus)
    }

    @Test
    fun `sendFrame returns false for empty opus data`() {
        val frame = AudioFrame(PacketType.AUDIO, 0, 0, ByteArray(0))

        val result = repository.sendFrame(frame)

        assertFalse(result)
        assertEquals(0, fakeConnection.binarySent.size)
    }

    @Test
    fun `sendFrame returns false when sendBinary fails`() {
        fakeConnection.sendBinaryResult = false
        val frame = AudioFrame(PacketType.AUDIO, 0, 0, byteArrayOf(0x01))

        val result = repository.sendFrame(frame)

        assertFalse(result)
    }

    @Test
    fun `sendFrame sends multiple frames in sequence`() {
        for (i in 0 until 5) {
            val frame = AudioFrame(PacketType.AUDIO, i.toLong(), i * 1000L, byteArrayOf(i.toByte()))
            repository.sendFrame(frame)
        }

        assertEquals(5, fakeConnection.binarySent.size)

        // Verify sequence numbers are correct in each packet
        for (i in 0 until 5) {
            val buf = ByteBuffer.wrap(fakeConnection.binarySent[i]).order(ByteOrder.BIG_ENDIAN)
            buf.get() // skip type
            assertEquals(i, buf.getInt())
        }
    }

    // ================================================================
    // startCapture / stopCapture
    // ================================================================

    @Test
    fun `startCapture starts pipeline`() {
        repository.startCapture()
        assertTrue(pipeline.isRunning)
    }

    @Test
    fun `startCapture with streamId starts pipeline`() {
        val testStreamId = java.util.UUID.randomUUID()
        repository.startCapture(testStreamId)
        assertTrue(pipeline.isRunning)
    }

    @Test
    fun `stopCapture stops pipeline`() {
        repository.startCapture()
        repository.stopCapture()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `stopCapture is safe when not started`() {
        repository.stopCapture() // Should not throw
        assertFalse(pipeline.isRunning)
    }
}

// ================================================================
// Test doubles
// ================================================================

/**
 * ConnectionRepository that records all sendBinary calls.
 */
private class RecordingConnectionRepository : ConnectionRepository {
    override val state = MutableStateFlow(ConnectionState.Disconnected)
    override val events: SharedFlow<ConnectionEvent> = MutableSharedFlow(extraBufferCapacity = 64)
    override suspend fun connect(serverUrl: String) {}
    override suspend fun disconnect() {}
    override fun sendText(message: String) = true

    var sendBinaryResult = true
    val binarySent = mutableListOf<ByteArray>()

    override fun sendBinary(data: ByteArray): Boolean {
        binarySent.add(data.copyOf())
        return sendBinaryResult
    }
}

private class StubAudioRecorder : AudioRecorder {
    override val isRecording = false
    override fun start() = true
    override fun stop() {}
    override fun read(buffer: ShortArray, offset: Int, size: Int) = -1
    override fun close() {}
}

private class StubOpusEncoder : OpusEncoder {
    override fun encode(pcm: ShortArray, frameSize: Int, output: ByteArray, maxOutput: Int) = -1
    override fun close() {}
}
