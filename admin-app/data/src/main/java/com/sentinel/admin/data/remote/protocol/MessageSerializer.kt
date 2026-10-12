package com.sentinel.admin.data.remote.protocol

import com.sentinel.shared.protocol.MessageType
import com.sentinel.shared.protocol.ProtocolVersion
import com.squareup.moshi.Moshi
import okio.Buffer

/**
 * Serializes outgoing control messages and deserializes incoming ones.
 *
 * Admin-specific:
 * - Serializes: AUTH, HEARTBEAT, LISTEN, STOP
 * - Deserializes: AUTH_ACK, HEARTBEAT_ACK, PONG, ERROR
 * - No REGISTER or LOCATION serialization (Admin is an observer)
 */
class MessageSerializer(private val moshi: Moshi = Moshi.Builder().build()) {

    // ---- Adapters (lazy, thread-safe) ----

    private val envelopeAdapter by lazy { moshi.adapter(EnvelopeJson::class.java) }
    private val authDataAdapter by lazy { moshi.adapter(AuthDataJson::class.java) }
    private val listenDataAdapter by lazy { moshi.adapter(ListenDataJson::class.java) }
    private val stopDataAdapter by lazy { moshi.adapter(StopDataJson::class.java) }
    private val ackMessageAdapter by lazy { moshi.adapter(AckMessageJson::class.java) }
    private val errorMessageAdapter by lazy { moshi.adapter(ErrorMessageJson::class.java) }
    private val deviceUpdateMessageAdapter by lazy { moshi.adapter(DeviceUpdateMessageJson::class.java) }
    private val filesListReqDataJsonAdapter by lazy { moshi.adapter(FilesListReqDataJson::class.java) }
    private val filesListResAdapter by lazy { moshi.adapter(FilesListResJson::class.java) }
    private val fileDownloadReqDataJsonAdapter by lazy { moshi.adapter(FileDownloadReqDataJson::class.java) }
    private val fileDownloadResAdapter by lazy { moshi.adapter(FileDownloadResJson::class.java) }
    private val fileChunkAckDataJsonAdapter by lazy { moshi.adapter(FileChunkAckDataJson::class.java) }
    private val fileStopReqDataJsonAdapter by lazy { moshi.adapter(FileStopReqDataJson::class.java) }

    // ============================================================
    // Outgoing serialization
    // ============================================================

    fun serializeAuth(token: String, sequence: Long): String {
        return buildEnvelope(MessageType.AUTH, sequence) { writer ->
            authDataAdapter.toJson(writer, AuthDataJson(token))
        }
    }

    fun serializeHeartbeat(sequence: Long): String {
        return buildEnvelope(MessageType.HEARTBEAT, sequence) { writer ->
            writer.beginObject()
            writer.endObject()
        }
    }

    fun serializeListen(
        deviceId: String,
        sequence: Long,
        streamId: String? = null,
        audioVersion: Int = 2
    ): String {
        return buildEnvelope(MessageType.LISTEN, sequence) { writer ->
            listenDataAdapter.toJson(writer, ListenDataJson(deviceId, streamId, audioVersion))
        }
    }

    fun serializeStop(
        deviceId: String,
        sequence: Long,
        streamId: String? = null
    ): String {
        return buildEnvelope(MessageType.STOP, sequence) { writer ->
            stopDataAdapter.toJson(writer, StopDataJson(deviceId, streamId))
        }
    }

    fun serializeFilesListReq(deviceId: String, path: String, sequence: Long): String {
        return buildEnvelope(MessageType.FILES_LIST_REQ, sequence) { writer ->
            filesListReqDataJsonAdapter.toJson(writer, FilesListReqDataJson(deviceId, path))
        }
    }

    fun serializeFileDownloadReq(deviceId: String, path: String, offset: Long, nonce: String, sequence: Long): String {
        return buildEnvelope(MessageType.FILE_DOWNLOAD_REQ, sequence) { writer ->
            fileDownloadReqDataJsonAdapter.toJson(writer, FileDownloadReqDataJson(deviceId, path, offset, nonce))
        }
    }

    fun serializeFileChunkAck(deviceId: String, path: String, ackSequence: Long, sequence: Long): String {
        return buildEnvelope(MessageType.FILE_CHUNK_ACK, sequence) { writer ->
            fileChunkAckDataJsonAdapter.toJson(writer, FileChunkAckDataJson(deviceId, path, ackSequence))
        }
    }

    fun serializeFileStopReq(deviceId: String, path: String, sequence: Long): String {
        return buildEnvelope(MessageType.FILE_STOP_REQ, sequence) { writer ->
            fileStopReqDataJsonAdapter.toJson(writer, FileStopReqDataJson(deviceId, path))
        }
    }

    // ============================================================
    // Incoming deserialization
    // ============================================================

    fun deserialize(json: String): IncomingMessage {
        val envelope = try {
            envelopeAdapter.fromJson(json)
        } catch (_: Exception) {
            null
        } ?: return IncomingMessage.Unknown("", 0)

        return when (envelope.type) {
            MessageType.AUTH_ACK -> {
                val msg = ackMessageAdapter.fromJson(json)
                IncomingMessage.AuthAck(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    success = msg?.data?.success ?: false
                )
            }

            MessageType.HEARTBEAT_ACK -> {
                IncomingMessage.HeartbeatAck(
                    type = envelope.type,
                    sequence = envelope.sequence
                )
            }

            MessageType.PONG -> {
                IncomingMessage.Pong(
                    type = envelope.type,
                    sequence = envelope.sequence
                )
            }

            MessageType.ERROR -> {
                val msg = errorMessageAdapter.fromJson(json)
                IncomingMessage.Error(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    code = msg?.data?.code ?: 0,
                    message = msg?.data?.message ?: ""
                )
            }

            MessageType.DEVICE_UPDATE -> {
                val msg = try {
                    deviceUpdateMessageAdapter.fromJson(json)
                } catch (_: Exception) {
                    null
                }
                if (msg != null) {
                    IncomingMessage.DeviceUpdate(
                        type = envelope.type,
                        sequence = envelope.sequence,
                        updateData = msg.data
                    )
                } else {
                    IncomingMessage.Unknown(envelope.type, envelope.sequence)
                }
            }

            MessageType.FILES_LIST_RES -> {
                val msg = filesListResAdapter.fromJson(json)
                IncomingMessage.FilesListRes(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    path = msg?.data?.path ?: "",
                    items = msg?.data?.items ?: emptyList()
                )
            }

            MessageType.FILE_DOWNLOAD_RES -> {
                val msg = fileDownloadResAdapter.fromJson(json)
                IncomingMessage.FileDownloadRes(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    path = msg?.data?.path ?: "",
                    success = msg?.data?.success ?: false,
                    size = msg?.data?.size ?: 0L,
                    sha256 = msg?.data?.sha256,
                    error = msg?.data?.error
                )
            }

            MessageType.COMMAND_RESULT -> {
                IncomingMessage.CommandResult(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    rawJson = json
                )
            }

            else -> IncomingMessage.Unknown(
                type = envelope.type,
                sequence = envelope.sequence
            )
        }
    }

    // ============================================================
    // Internal
    // ============================================================

    /**
     * Builds the standard protocol envelope JSON.
     * The [writeData] lambda writes the "data" field content.
     */
    private fun buildEnvelope(
        type: String,
        sequence: Long,
        writeData: (com.squareup.moshi.JsonWriter) -> Unit
    ): String {
        val buffer = Buffer()
        val writer = com.squareup.moshi.JsonWriter.of(buffer)
        writer.beginObject()
        writer.name("type").value(type)
        writer.name("version").value(ProtocolVersion.CURRENT.toLong())
        writer.name("timestamp").value(System.currentTimeMillis())
        writer.name("sequence").value(sequence)
        writer.name("data")
        writeData(writer)
        writer.endObject()
        writer.close()
        return buffer.readUtf8()
    }
}
