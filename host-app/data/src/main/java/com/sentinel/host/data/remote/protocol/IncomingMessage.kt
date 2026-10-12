package com.sentinel.host.data.remote.protocol

/**
 * Deserialized incoming control message from the server.
 * Each variant maps to a specific protocol message type.
 */
sealed interface IncomingMessage {
    val type: String
    val sequence: Long

    data class AuthAck(
        override val type: String,
        override val sequence: Long,
        val success: Boolean
    ) : IncomingMessage

    data class RegisterAck(
        override val type: String,
        override val sequence: Long,
        val success: Boolean
    ) : IncomingMessage

    data class HeartbeatAck(
        override val type: String,
        override val sequence: Long
    ) : IncomingMessage

    data class Pong(
        override val type: String,
        override val sequence: Long
    ) : IncomingMessage

    data class Error(
        override val type: String,
        override val sequence: Long,
        val code: Int,
        val message: String
    ) : IncomingMessage

    data class FilesListReq(
        override val type: String,
        override val sequence: Long,
        val path: String
    ) : IncomingMessage

    data class FileDownloadReq(
        override val type: String,
        override val sequence: Long,
        val path: String,
        val offset: Long,
        val nonce: String
    ) : IncomingMessage

    data class FileChunkAck(
        override val type: String,
        override val sequence: Long,
        val path: String,
        val ackSequence: Long
    ) : IncomingMessage

    data class FileStopReq(
        override val type: String,
        override val sequence: Long,
        val path: String
    ) : IncomingMessage

    data class Listen(
        override val type: String,
        override val sequence: Long,
        val deviceId: String = "",
        val streamId: String? = null,
        val maxDurationSeconds: Long? = null
    ) : IncomingMessage

    data class Stop(
        override val type: String,
        override val sequence: Long,
        val deviceId: String = "",
        val streamId: String? = null
    ) : IncomingMessage

    data class Unknown(
        override val type: String,
        override val sequence: Long
    ) : IncomingMessage
}
