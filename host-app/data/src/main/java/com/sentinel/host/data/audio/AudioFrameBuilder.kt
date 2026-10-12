package com.sentinel.host.data.audio

import com.sentinel.host.domain.model.AudioFrame
import com.sentinel.shared.protocol.AudioConstants
import com.sentinel.shared.protocol.PacketType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes [AudioFrame] into a binary packet for WebSocket transmission.
 *
 * Binary layout V1 (13 bytes):
 * ```
 * [0]      PacketType    1 byte   (0x01 = AUDIO)
 * [1..4]   Sequence      4 bytes  (Big-Endian uint32)
 * [5..12]  Timestamp     8 bytes  (Big-Endian int64, epoch millis)
 * [13..]   OpusData      N bytes  (variable, Opus encoded audio)
 * ```
 *
 * Binary layout V2 (29 bytes):
 * ```
 * [0]      PacketType    1 byte   (0x03 = AUDIO_V2)
 * [1..16]  StreamID      16 bytes (Big-Endian raw UUID: 8B MSB + 8B LSB)
 * [17..20] Sequence      4 bytes  (Big-Endian uint32)
 * [21..28] Timestamp     8 bytes  (Big-Endian int64, epoch millis)
 * [29..]   OpusData      N bytes  (variable, Opus encoded audio)
 * ```
 */
class AudioFrameBuilder {

    companion object {
        /** Maximum Opus encoded frame size in bytes. */
        private const val MAX_OPUS_FRAME_SIZE = 4000

        /** Maximum total packet size = header V2 + max Opus frame. */
        private const val MAX_PACKET_SIZE = AudioConstants.HEADER_V2_SIZE + MAX_OPUS_FRAME_SIZE
    }

    /**
     * Serializes an [AudioFrame] into a binary packet.
     *
     * Supports both AUDIO (V1, 13B header) and AUDIO_V2 (29B header with 16B UUID).
     *
     * @param frame The audio frame to serialize.
     * @return A new ByteArray containing the binary packet, or null if the frame is invalid.
     */
    fun build(frame: AudioFrame): ByteArray? {
        if (frame.opusData.isEmpty()) return null

        val isV2 = frame.packetType == PacketType.AUDIO_V2 && frame.streamId != null
        val headerSize = if (isV2) AudioConstants.HEADER_V2_SIZE else AudioConstants.HEADER_SIZE
        val totalSize = headerSize + frame.opusData.size
        if (totalSize > MAX_PACKET_SIZE) return null

        return try {
            val result = ByteArray(totalSize)
            val buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
            buffer.put(frame.packetType)
            if (isV2) {
                val streamId = frame.streamId!!
                buffer.putLong(streamId.mostSignificantBits)
                buffer.putLong(streamId.leastSignificantBits)
            }
            buffer.putInt(frame.sequence.toInt())
            buffer.putLong(frame.timestamp)
            buffer.put(frame.opusData)
            result
        } catch (e: Exception) {
            null
        }
    }
}
