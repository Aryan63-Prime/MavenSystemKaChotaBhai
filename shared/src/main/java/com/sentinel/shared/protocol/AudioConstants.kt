package com.sentinel.shared.protocol

/**
 * Audio frame header size in bytes per PROTOCOL.md.
 * Layout: [1B PacketType][4B Sequence][8B Timestamp] = 13 bytes.
 */
object AudioConstants {
    const val HEADER_SIZE = 13
    const val HEADER_V2_SIZE = 29
    const val STREAM_ID_SIZE = 16
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_DURATION_MS / 1000
    const val INITIAL_BITRATE = 48000
}
