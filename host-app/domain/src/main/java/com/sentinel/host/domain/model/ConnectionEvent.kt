package com.sentinel.host.domain.model

/**
 * Events emitted during the connection lifecycle.
 * The supervisor consumes events and derives state.
 *
 * Supervisor → Events → State
 * (not Supervisor → State → Guess)
 */
sealed interface ConnectionEvent {
    data object Connected : ConnectionEvent
    data object Authenticated : ConnectionEvent
    open class Registered(val token: String? = null) : ConnectionEvent {
        companion object : Registered(null)
        override fun equals(other: Any?): Boolean = other is Registered
        override fun hashCode(): Int = Registered::class.hashCode()
    }
    data object HeartbeatAck : ConnectionEvent
    data class Error(val code: Int, val message: String) : ConnectionEvent
    data object Disconnected : ConnectionEvent

    // File events
    data class FilesListReq(val sequence: Long, val path: String) : ConnectionEvent
    data class FileDownloadReq(val sequence: Long, val path: String, val offset: Long, val nonce: String) : ConnectionEvent
    data class FileChunkAck(val sequence: Long, val path: String, val ackSequence: Long) : ConnectionEvent
    data class FileStopReq(val sequence: Long, val path: String) : ConnectionEvent

    // Audio on-demand session events
    data class StartAudio(
        val sequence: Long,
        val deviceId: String,
        val streamId: String? = null,
        val maxDurationSeconds: Long? = null
    ) : ConnectionEvent
    data class StopAudio(
        val sequence: Long,
        val deviceId: String,
        val streamId: String? = null
    ) : ConnectionEvent

    // Sprint A6: Reconnection events
    data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionEvent
    data class ReconnectFailed(val attempt: Int, val reason: String) : ConnectionEvent
    data object ReconnectExhausted : ConnectionEvent
}
