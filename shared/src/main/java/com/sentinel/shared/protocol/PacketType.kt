package com.sentinel.shared.protocol

/**
 * Binary audio frame packet type per PROTOCOL.md.
 * Used as the first byte of every binary WebSocket frame.
 */
object PacketType {
    const val AUDIO: Byte = 0x01
    const val FILE_CHUNK: Byte = 0x02
    const val AUDIO_V2: Byte = 0x03
}
