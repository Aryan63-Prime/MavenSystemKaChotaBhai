package com.sentinel.host.data.audio

import com.sentinel.host.domain.model.AudioFrame
import com.sentinel.shared.protocol.AudioConstants
import com.sentinel.shared.protocol.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioFrameBuilderTest {

    private lateinit var builder: AudioFrameBuilder

    @Before
    fun setUp() {
        builder = AudioFrameBuilder()
    }

    // ================================================================
    // Binary layout verification
    // ================================================================

    @Test
    fun `packet starts with correct packet type`() {
        val frame = createFrame(sequence = 0, timestamp = 0, opusSize = 10)
        val packet = builder.build(frame)!!

        assertEquals(PacketType.AUDIO, packet[0])
    }

    @Test
    fun `header has correct total size`() {
        val opusData = ByteArray(100) { it.toByte() }
        val frame = createFrame(sequence = 0, timestamp = 0, opusData = opusData)
        val packet = builder.build(frame)!!

        assertEquals(AudioConstants.HEADER_SIZE + opusData.size, packet.size)
    }

    @Test
    fun `sequence is big-endian at offset 1`() {
        val frame = createFrame(sequence = 0x01020304, timestamp = 0, opusSize = 1)
        val packet = builder.build(frame)!!

        // Big-endian: 01 02 03 04
        assertEquals(0x01.toByte(), packet[1])
        assertEquals(0x02.toByte(), packet[2])
        assertEquals(0x03.toByte(), packet[3])
        assertEquals(0x04.toByte(), packet[4])
    }

    @Test
    fun `timestamp is big-endian at offset 5`() {
        val timestamp = 0x0102030405060708L
        val frame = createFrame(sequence = 0, timestamp = timestamp, opusSize = 1)
        val packet = builder.build(frame)!!

        // Big-endian: 01 02 03 04 05 06 07 08
        assertEquals(0x01.toByte(), packet[5])
        assertEquals(0x02.toByte(), packet[6])
        assertEquals(0x03.toByte(), packet[7])
        assertEquals(0x04.toByte(), packet[8])
        assertEquals(0x05.toByte(), packet[9])
        assertEquals(0x06.toByte(), packet[10])
        assertEquals(0x07.toByte(), packet[11])
        assertEquals(0x08.toByte(), packet[12])
    }

    @Test
    fun `opus data starts at offset 13`() {
        val opusData = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val frame = createFrame(sequence = 0, timestamp = 0, opusData = opusData)
        val packet = builder.build(frame)!!

        assertEquals(0xAA.toByte(), packet[13])
        assertEquals(0xBB.toByte(), packet[14])
        assertEquals(0xCC.toByte(), packet[15])
    }

    @Test
    fun `opus payload is preserved exactly`() {
        val opusData = ByteArray(64) { (it * 7).toByte() }
        val frame = createFrame(sequence = 0, timestamp = 0, opusData = opusData)
        val packet = builder.build(frame)!!

        val extractedOpus = packet.copyOfRange(AudioConstants.HEADER_SIZE, packet.size)
        assertArrayEquals(opusData, extractedOpus)
    }

    // ================================================================
    // Big-endian verification via ByteBuffer
    // ================================================================

    @Test
    fun `packet can be parsed back with big-endian ByteBuffer`() {
        val seq = 42L
        val ts = System.currentTimeMillis()
        val opusData = ByteArray(50) { it.toByte() }
        val frame = AudioFrame(PacketType.AUDIO, seq, ts, opusData)
        val packet = builder.build(frame)!!

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        assertEquals(PacketType.AUDIO, buf.get())
        assertEquals(seq.toInt(), buf.getInt())
        assertEquals(ts, buf.getLong())

        val parsedOpus = ByteArray(opusData.size)
        buf.get(parsedOpus)
        assertArrayEquals(opusData, parsedOpus)
    }

    // ================================================================
    // Edge cases
    // ================================================================

    @Test
    fun `returns null for empty opus data`() {
        val frame = createFrame(sequence = 0, timestamp = 0, opusData = ByteArray(0))
        assertNull(builder.build(frame))
    }

    @Test
    fun `returns null for oversized opus data`() {
        val frame = createFrame(sequence = 0, timestamp = 0, opusData = ByteArray(5000))
        assertNull(builder.build(frame))
    }

    @Test
    fun `handles minimum opus frame (1 byte)`() {
        val frame = createFrame(sequence = 0, timestamp = 0, opusSize = 1)
        val packet = builder.build(frame)!!

        assertEquals(AudioConstants.HEADER_SIZE + 1, packet.size)
    }

    @Test
    fun `handles maximum opus frame (4000 bytes)`() {
        val frame = createFrame(sequence = 0, timestamp = 0, opusSize = 4000)
        val packet = builder.build(frame)!!

        assertEquals(AudioConstants.HEADER_SIZE + 4000, packet.size)
    }

    // ================================================================
    // Reusability
    // ================================================================

    @Test
    fun `builder can be reused for multiple frames`() {
        for (i in 0 until 10) {
            val frame = createFrame(sequence = i.toLong(), timestamp = i * 1000L, opusSize = 64)
            val packet = builder.build(frame)
            assertNotNull("Frame $i should build", packet)

            val buf = ByteBuffer.wrap(packet!!).order(ByteOrder.BIG_ENDIAN)
            buf.get() // skip packet type
            assertEquals("Sequence mismatch at $i", i, buf.getInt())
        }
    }

    @Test
    fun `builder produces independent packets`() {
        val frame1 = createFrame(sequence = 1, timestamp = 100, opusSize = 10)
        val frame2 = createFrame(sequence = 2, timestamp = 200, opusSize = 20)

        val packet1 = builder.build(frame1)!!
        val packet2 = builder.build(frame2)!!

        // Verify they are independent
        assertTrue(packet1.size != packet2.size)

        val buf1 = ByteBuffer.wrap(packet1).order(ByteOrder.BIG_ENDIAN)
        buf1.get()
        assertEquals(1, buf1.getInt())

        val buf2 = ByteBuffer.wrap(packet2).order(ByteOrder.BIG_ENDIAN)
        buf2.get()
        assertEquals(2, buf2.getInt())
    }

    // ================================================================
    // AUDIO_V2 tests (29-byte header with UUID)
    // ================================================================

    @Test
    fun `AUDIO_V2 packet starts with 0x03 and has 29-byte header`() {
        val streamId = java.util.UUID.randomUUID()
        val opusData = ByteArray(50) { it.toByte() }
        val frame = AudioFrame(
            packetType = PacketType.AUDIO_V2,
            sequence = 10,
            timestamp = 5000L,
            opusData = opusData,
            streamId = streamId
        )

        val packet = builder.build(frame)!!

        assertEquals(PacketType.AUDIO_V2, packet[0])
        assertEquals(AudioConstants.HEADER_V2_SIZE + opusData.size, packet.size)
    }

    @Test
    fun `AUDIO_V2 streamId is big-endian at offset 1`() {
        val streamId = java.util.UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L)
        val frame = AudioFrame(
            packetType = PacketType.AUDIO_V2,
            sequence = 42,
            timestamp = 1000L,
            opusData = byteArrayOf(0x55),
            streamId = streamId
        )

        val packet = builder.build(frame)!!

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        assertEquals(PacketType.AUDIO_V2, buf.get())
        assertEquals(0x0102030405060708L, buf.getLong())
        assertEquals(0x090A0B0C0D0E0F10L, buf.getLong())
        assertEquals(42, buf.getInt())
        assertEquals(1000L, buf.getLong())
        assertEquals(0x55.toByte(), buf.get())
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun createFrame(
        sequence: Long,
        timestamp: Long,
        opusSize: Int = 64,
        opusData: ByteArray? = null
    ): AudioFrame {
        val data = opusData ?: ByteArray(opusSize) { it.toByte() }
        return AudioFrame(PacketType.AUDIO, sequence, timestamp, data)
    }
}
