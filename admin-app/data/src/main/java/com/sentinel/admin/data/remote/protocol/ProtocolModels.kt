package com.sentinel.admin.data.remote.protocol

import com.squareup.moshi.JsonClass

/**
 * Moshi-serializable protocol models.
 * Internal transport types — never exposed to the domain layer.
 *
 * Admin-specific:
 * - No RegisterDataJson (Admin doesn't register as device)
 * - No LocationDataJson (Admin doesn't publish location)
 * - Adds ListenDataJson and StopDataJson for audio control
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
internal data class ListenDataJson(
    val deviceId: String,
    val streamId: String? = null,
    val audioVersion: Int = 2
)

@JsonClass(generateAdapter = true)
internal data class StopDataJson(
    val deviceId: String,
    val streamId: String? = null
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
data class FileItemJson(
    val name: String,
    val is_dir: Boolean,
    val size: Long,
    val last_modified: Long
)

@JsonClass(generateAdapter = true)
internal data class FilesListReqDataJson(
    val deviceId: String,
    val path: String
)

@JsonClass(generateAdapter = true)
internal data class FilesListReqJson(
    val type: String = "",
    val data: FilesListReqDataJson
)

@JsonClass(generateAdapter = true)
internal data class FilesListResDataJson(
    val path: String,
    val items: List<FileItemJson>
)

@JsonClass(generateAdapter = true)
internal data class FilesListResJson(
    val type: String = "",
    val data: FilesListResDataJson
)

@JsonClass(generateAdapter = true)
internal data class FileDownloadReqDataJson(
    val deviceId: String,
    val path: String,
    val offset: Long,
    val nonce: String
)

@JsonClass(generateAdapter = true)
internal data class FileDownloadReqJson(
    val type: String = "",
    val data: FileDownloadReqDataJson
)

@JsonClass(generateAdapter = true)
internal data class FileDownloadResDataJson(
    val path: String,
    val success: Boolean,
    val size: Long,
    val sha256: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
internal data class FileDownloadResJson(
    val type: String = "",
    val data: FileDownloadResDataJson
)

@JsonClass(generateAdapter = true)
internal data class FileChunkAckDataJson(
    val deviceId: String,
    val path: String,
    val sequence: Long
)

@JsonClass(generateAdapter = true)
internal data class FileChunkAckJson(
    val type: String = "",
    val data: FileChunkAckDataJson
)

@JsonClass(generateAdapter = true)
internal data class FileStopReqDataJson(
    val deviceId: String,
    val path: String
)

@JsonClass(generateAdapter = true)
internal data class FileStopReqJson(
    val type: String = "",
    val data: FileStopReqDataJson
)
