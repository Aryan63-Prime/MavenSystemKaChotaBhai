package com.sentinel.host.data.remote.protocol

import com.squareup.moshi.JsonClass

/**
 * Moshi-serializable protocol models.
 * These are internal transport types — never exposed to the domain layer.
 *
 * Outgoing payloads: serialized into the "data" field of the protocol envelope.
 * Incoming models: full envelope + typed data for deserialization.
 */

// ============================================================
// Envelope — used to extract "type" from any incoming message
// ============================================================

@JsonClass(generateAdapter = true)
internal data class EnvelopeJson(
    val type: String = "",
    val version: Int = 0,
    val timestamp: Long = 0,
    val sequence: Long = 0
)

// ============================================================
// Outgoing data payloads
// ============================================================

@JsonClass(generateAdapter = true)
internal data class AuthDataJson(
    val token: String
)

@JsonClass(generateAdapter = true)
internal data class RegisterDataJson(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
    val model: String,
    val fcmToken: String? = null
)

@JsonClass(generateAdapter = true)
internal data class LocationDataJson(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val battery: Int,
    val network: String
)

// ============================================================
// Incoming full messages (envelope + typed data)
// ============================================================

@JsonClass(generateAdapter = true)
internal data class AckDataJson(
    val success: Boolean = false
)

@JsonClass(generateAdapter = true)
internal data class AckMessageJson(
    val type: String = "",
    val version: Int = 0,
    val timestamp: Long = 0,
    val sequence: Long = 0,
    val data: AckDataJson = AckDataJson()
)

@JsonClass(generateAdapter = true)
internal data class ErrorDataJson(
    val code: Int = 0,
    val message: String = ""
)

@JsonClass(generateAdapter = true)
internal data class ErrorMessageJson(
    val type: String = "",
    val version: Int = 0,
    val timestamp: Long = 0,
    val sequence: Long = 0,
    val data: ErrorDataJson = ErrorDataJson()
)

// ============================================================
// File Messages
// ============================================================

@JsonClass(generateAdapter = true)
internal data class FilesListReqDataJson(
    val path: String = ""
)

@JsonClass(generateAdapter = true)
internal data class FilesListReqJson(
    val type: String = "",
    val data: FilesListReqDataJson = FilesListReqDataJson()
)

@JsonClass(generateAdapter = true)
internal data class FileDownloadReqDataJson(
    val path: String = "",
    val offset: Long = 0L,
    val nonce: String = ""
)

@JsonClass(generateAdapter = true)
internal data class FileDownloadReqJson(
    val type: String = "",
    val data: FileDownloadReqDataJson = FileDownloadReqDataJson()
)

@JsonClass(generateAdapter = true)
internal data class FileChunkAckDataJson(
    val path: String = "",
    val sequence: Long = 0L
)

@JsonClass(generateAdapter = true)
internal data class FileChunkAckJson(
    val type: String = "",
    val data: FileChunkAckDataJson = FileChunkAckDataJson()
)

@JsonClass(generateAdapter = true)
internal data class FileStopReqDataJson(
    val path: String = ""
)

@JsonClass(generateAdapter = true)
internal data class FileStopReqJson(
    val type: String = "",
    val data: FileStopReqDataJson = FileStopReqDataJson()
)

// ============================================================
// Audio Control Messages
// ============================================================

@JsonClass(generateAdapter = true)
internal data class ListenDataJson(
    val deviceId: String = "",
    val streamId: String? = null,
    val maxDurationSeconds: Long? = null
)

@JsonClass(generateAdapter = true)
internal data class ListenMessageJson(
    val type: String = "",
    val data: ListenDataJson = ListenDataJson()
)

@JsonClass(generateAdapter = true)
internal data class StopDataJson(
    val deviceId: String = "",
    val streamId: String? = null
)

@JsonClass(generateAdapter = true)
internal data class StopMessageJson(
    val type: String = "",
    val data: StopDataJson = StopDataJson()
)
