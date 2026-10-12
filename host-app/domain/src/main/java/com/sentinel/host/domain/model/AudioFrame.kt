package com.sentinel.host.domain.model

import java.util.UUID

/**
 * A single encoded audio frame ready for binary WebSocket transmission.
 * Layout V1 (13B): [1B packetType][4B sequence][8B timestamp][opusData]
 * Layout V2 (29B): [1B packetType][16B streamId][4B sequence][8B timestamp][opusData]
 */
data class AudioFrame(
    val packetType: Byte,
    val sequence: Long,
    val timestamp: Long,
    val opusData: ByteArray,
    val streamId: UUID? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return packetType == other.packetType &&
            sequence == other.sequence &&
            timestamp == other.timestamp &&
            streamId == other.streamId &&
            opusData.contentEquals(other.opusData)
    }

    override fun hashCode(): Int {
        var result = packetType.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (streamId?.hashCode() ?: 0)
        result = 31 * result + opusData.contentHashCode()
        return result
    }
}
