package com.sentinel.host.data.repository

import android.util.Log
import com.sentinel.host.data.remote.protocol.IncomingMessage
import com.sentinel.host.data.remote.protocol.MessageSerializer
import com.sentinel.host.data.remote.websocket.WebSocketDataSource
import com.sentinel.host.data.remote.websocket.WebSocketState
import com.sentinel.host.domain.model.ConnectionEvent
import com.sentinel.host.domain.model.ConnectionState
import com.sentinel.host.domain.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Bridges transport-level WebSocket to domain.
 *
 * Responsibilities:
 * - Maps WebSocketState → domain ConnectionState (transport-level only)
 * - Deserializes incoming text messages → emits ConnectionEvents
 * - Emits WebSocket state changes as ConnectionEvents
 *
 * This class does NOT manage business-level state transitions
 * (Authenticating, Registering, Ready). That's the Supervisor's job.
 */
class ConnectionRepositoryImpl(
    private val webSocketDataSource: WebSocketDataSource,
    private val messageSerializer: MessageSerializer,
    scope: CoroutineScope
) : ConnectionRepository {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    init {
        // Observe WebSocket state → emit transport events + update state
        webSocketDataSource.state
            .onEach { wsState ->
                _state.value = wsState.toDomainState()
                wsState.toEvent()?.let { _events.emit(it) }
            }
            .launchIn(scope)

        // Observe incoming text -> deserialize -> emit domain events
        webSocketDataSource.textMessages
            .onEach { json ->
                Log.d("Sentinel:WS", "Incoming text: $json")
                val message = messageSerializer.deserialize(json)
                message.toEvent()?.let { _events.emit(it) }
            }
            .launchIn(scope)
    }

    override suspend fun connect(serverUrl: String) {
        webSocketDataSource.connect(serverUrl)
    }

    override suspend fun disconnect() {
        webSocketDataSource.disconnect()
    }

    override fun sendText(message: String): Boolean {
        return webSocketDataSource.sendText(message)
    }

    override fun sendBinary(data: ByteArray): Boolean {
        return webSocketDataSource.sendBinary(data)
    }
}

/**
 * Maps transport WebSocketState → domain ConnectionState.
 * Only transport-level states are mapped here.
 */
internal fun WebSocketState.toDomainState(): ConnectionState = when (this) {
    is WebSocketState.Disconnected -> ConnectionState.Disconnected
    is WebSocketState.Connecting -> ConnectionState.Connecting
    is WebSocketState.Connected -> ConnectionState.Connected
    is WebSocketState.Disconnecting -> ConnectionState.Disconnected
    is WebSocketState.Failed -> ConnectionState.Error(reason)
}

/**
 * Maps WebSocket state changes → ConnectionEvents.
 */
internal fun WebSocketState.toEvent(): ConnectionEvent? = when (this) {
    is WebSocketState.Connected -> ConnectionEvent.Connected
    is WebSocketState.Disconnected -> ConnectionEvent.Disconnected
    is WebSocketState.Failed -> ConnectionEvent.Error(code ?: 0, reason)
    else -> null
}

/**
 * Maps deserialized incoming messages → ConnectionEvents.
 */
internal fun IncomingMessage.toEvent(): ConnectionEvent? = when (this) {
    is IncomingMessage.AuthAck -> if (success) {
        ConnectionEvent.Authenticated
    } else {
        ConnectionEvent.Error(401, "Authentication rejected")
    }
    is IncomingMessage.RegisterAck -> if (success) {
        ConnectionEvent.Registered(token)
    } else {
        ConnectionEvent.Error(409, "Registration rejected")
    }
    is IncomingMessage.HeartbeatAck -> ConnectionEvent.HeartbeatAck
    is IncomingMessage.Error -> ConnectionEvent.Error(code, message)
    is IncomingMessage.FilesListReq -> ConnectionEvent.FilesListReq(sequence, path)
    is IncomingMessage.FileDownloadReq -> ConnectionEvent.FileDownloadReq(sequence, path, offset, nonce)
    is IncomingMessage.FileChunkAck -> ConnectionEvent.FileChunkAck(sequence, path, ackSequence)
    is IncomingMessage.FileStopReq -> ConnectionEvent.FileStopReq(sequence, path)
    is IncomingMessage.Listen -> ConnectionEvent.StartAudio(sequence, deviceId, streamId, maxDurationSeconds)
    is IncomingMessage.Stop -> ConnectionEvent.StopAudio(sequence, deviceId, streamId)
    is IncomingMessage.Pong -> null
    is IncomingMessage.Unknown -> null
}
