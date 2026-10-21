package com.sentinel.host.data.remote.protocol

import com.sentinel.shared.protocol.MessageType
import com.sentinel.shared.protocol.ProtocolVersion
import com.squareup.moshi.Moshi
import okio.Buffer

/**
 * Serializes outgoing control messages and deserializes incoming ones.
 *
 * Outgoing: builds the protocol envelope with JsonWriter, delegates
 * the "data" field to typed Moshi adapters.
 *
 * Incoming: extracts "type" from the envelope, then re-parses the
 * full message with the correct typed adapter.
 */
class MessageSerializer(private val moshi: Moshi = Moshi.Builder().build()) {

    // ---- Adapters (lazy, thread-safe) ----

    private val envelopeAdapter by lazy { moshi.adapter(EnvelopeJson::class.java) }
    private val authDataAdapter by lazy { moshi.adapter(AuthDataJson::class.java) }
    private val registerDataAdapter by lazy { moshi.adapter(RegisterDataJson::class.java) }
    private val locationDataAdapter by lazy { moshi.adapter(LocationDataJson::class.java) }
    private val ackMessageAdapter by lazy { moshi.adapter(AckMessageJson::class.java) }
    private val errorMessageAdapter by lazy { moshi.adapter(ErrorMessageJson::class.java) }
    private val filesListReqAdapter by lazy { moshi.adapter(FilesListReqJson::class.java) }
    private val fileDownloadReqAdapter by lazy { moshi.adapter(FileDownloadReqJson::class.java) }
    private val fileChunkAckAdapter by lazy { moshi.adapter(FileChunkAckJson::class.java) }
    private val fileStopReqAdapter by lazy { moshi.adapter(FileStopReqJson::class.java) }
    private val listenMessageAdapter by lazy { moshi.adapter(ListenMessageJson::class.java) }
    private val stopMessageAdapter by lazy { moshi.adapter(StopMessageJson::class.java) }

    // ============================================================
    // Outgoing serialization
    // ============================================================

    fun serializeAuth(token: String, sequence: Long): String {
        return buildEnvelope(MessageType.AUTH, sequence) { writer ->
            authDataAdapter.toJson(writer, AuthDataJson(token))
        }
    }

    fun serializeRegister(
        deviceId: String,
        deviceName: String,
        appVersion: String,
        model: String,
        sequence: Long,
        fcmToken: String? = null
    ): String {
        val data = RegisterDataJson(deviceId, deviceName, appVersion, model, fcmToken)
        return buildEnvelope(MessageType.REGISTER, sequence) { writer ->
            registerDataAdapter.toJson(writer, data)
        }
    }

    fun serializeHeartbeat(sequence: Long): String {
        return buildEnvelope(MessageType.HEARTBEAT, sequence) { writer ->
            writer.beginObject()
            writer.endObject()
        }
    }

    fun serializeLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        battery: Int,
        network: String,
        sequence: Long
    ): String {
        val data = LocationDataJson(latitude, longitude, accuracy, battery, network)
        return buildEnvelope(MessageType.LOCATION, sequence) { writer ->
            locationDataAdapter.toJson(writer, data)
        }
    }

    fun serializeError(sequence: Long, code: Int, message: String): String {
        return buildEnvelope(MessageType.ERROR, sequence) { writer ->
            errorMessageAdapter.toJson(writer, ErrorMessageJson(data = ErrorDataJson(code, message)))
        }
    }

    // ============================================================
    // Incoming deserialization
    // ============================================================

    fun deserialize(json: String): IncomingMessage {
        val envelope = envelopeAdapter.fromJson(json)
            ?: return IncomingMessage.Unknown("", 0)

        return when (envelope.type) {
            MessageType.AUTH_ACK -> {
                val msg = ackMessageAdapter.fromJson(json)
                IncomingMessage.AuthAck(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    success = msg?.data?.success ?: false
                )
            }

            MessageType.REGISTER_ACK -> {
                val msg = ackMessageAdapter.fromJson(json)
                IncomingMessage.RegisterAck(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    success = msg?.data?.success ?: false,
                    token = msg?.data?.token
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

            MessageType.FILES_LIST_REQ -> {
                val msg = filesListReqAdapter.fromJson(json)
                IncomingMessage.FilesListReq(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    path = msg?.data?.path ?: ""
                )
            }

            MessageType.FILE_DOWNLOAD_REQ -> {
                val msg = fileDownloadReqAdapter.fromJson(json)
                IncomingMessage.FileDownloadReq(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    path = msg?.data?.path ?: "",
                    offset = msg?.data?.offset ?: 0L,
                    nonce = msg?.data?.nonce ?: ""
                )
            }

            MessageType.FILE_CHUNK_ACK -> {
                val msg = fileChunkAckAdapter.fromJson(json)
                IncomingMessage.FileChunkAck(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    path = msg?.data?.path ?: "",
                    ackSequence = msg?.data?.sequence ?: 0L
                )
            }

            MessageType.FILE_STOP_REQ -> {
                val msg = fileStopReqAdapter.fromJson(json)
                IncomingMessage.FileStopReq(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    path = msg?.data?.path ?: ""
                )
            }

            MessageType.LISTEN -> {
                val msg = listenMessageAdapter.fromJson(json)
                IncomingMessage.Listen(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    deviceId = msg?.data?.deviceId ?: "",
                    streamId = msg?.data?.streamId,
                    maxDurationSeconds = msg?.data?.maxDurationSeconds
                )
            }

            MessageType.STOP -> {
                val msg = stopMessageAdapter.fromJson(json)
                IncomingMessage.Stop(
                    type = envelope.type,
                    sequence = envelope.sequence,
                    deviceId = msg?.data?.deviceId ?: "",
                    streamId = msg?.data?.streamId
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
