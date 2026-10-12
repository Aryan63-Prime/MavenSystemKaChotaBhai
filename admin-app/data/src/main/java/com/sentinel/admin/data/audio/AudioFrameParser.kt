package com.sentinel.admin.data.audio

import com.sentinel.shared.protocol.AudioConstants
import com.sentinel.shared.protocol.PacketType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Parses binary audio frames per PROTOCOL.md.
 *
 * Frame layout V1 (13 bytes):
 * [1B PacketType (0x01)][4B Sequence (big-endian uint32)][8B Timestamp (big-endian int64)][...Opus payload]
 *
 * Frame layout V2 (29 bytes):
 * [1B PacketType (0x03)][16B StreamID (UUID: 8B MSB + 8B LSB)][4B Sequence][8B Timestamp][...Opus payload]
 */
object AudioFrameParser {

    /**
     * Parses a binary frame into an [AudioFrame].
     * Returns null if validation fails — never throws.
     *
     * Validation:
     * - For AUDIO (0x01): Frame size >= HEADER_SIZE (13) and payload > 0
     * - For AUDIO_V2 (0x03): Frame size >= HEADER_V2_SIZE (29) and payload > 0
     */
    fun parse(data: ByteArray): AudioFrame? {
        if (data.size < AudioConstants.HEADER_SIZE) return null

        val packetType = data[0]
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        return when (packetType) {
            PacketType.AUDIO -> {
                val sequence = buffer.getInt(1).toLong() and 0xFFFFFFFFL
                val timestamp = buffer.getLong(5)
                val payloadSize = data.size - AudioConstants.HEADER_SIZE
                if (payloadSize <= 0) return null

                val payload = ByteArray(payloadSize)
                System.arraycopy(data, AudioConstants.HEADER_SIZE, payload, 0, payloadSize)

                AudioFrame(
                    packetType = packetType,
                    sequence = sequence,
                    timestamp = timestamp,
                    payload = payload,
                    streamId = null
                )
            }

            PacketType.AUDIO_V2 -> {
                if (data.size < AudioConstants.HEADER_V2_SIZE) return null
                val streamId = UUID(buffer.getLong(1), buffer.getLong(9))
                val sequence = buffer.getInt(17).toLong() and 0xFFFFFFFFL
                val timestamp = buffer.getLong(21)
                val payloadSize = data.size - AudioConstants.HEADER_V2_SIZE
                if (payloadSize <= 0) return null

                val payload = ByteArray(payloadSize)
                System.arraycopy(data, AudioConstants.HEADER_V2_SIZE, payload, 0, payloadSize)

                AudioFrame(
                    packetType = packetType,
                    sequence = sequence,
                    timestamp = timestamp,
                    payload = payload,
                    streamId = streamId
                )
            }

            else -> null
        }
    }
}

/**
 * Parsed audio frame with validated header.
 *
 * @property sequence UInt32 stored as Long (0..4294967295).
 * @property streamId 16-byte UUID for AUDIO_V2 streams, null for legacy AUDIO_V1.
 */
data class AudioFrame(
    val packetType: Byte,
    val sequence: Long,
    val timestamp: Long,
    val payload: ByteArray,
    val streamId: UUID? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return packetType == other.packetType &&
            sequence == other.sequence &&
            timestamp == other.timestamp &&
            streamId == other.streamId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = packetType.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (streamId?.hashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
