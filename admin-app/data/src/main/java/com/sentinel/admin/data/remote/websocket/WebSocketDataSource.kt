package com.sentinel.admin.data.remote.websocket

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Manages the raw OkHttp WebSocket connection for the Admin app.
 *
 * Thread safety:
 * - State updates use MutableStateFlow (atomic).
 * - OkHttp WebSocket.send() is internally thread-safe.
 * - Incoming messages are emitted to SharedFlow (thread-safe).
 * - connect/disconnect synchronize on the WebSocket reference.
 */
class WebSocketDataSource(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "Sentinel:AdminWS"
        private const val NORMAL_CLOSURE = 1000
        private const val CLOSE_REASON = "Admin disconnect"
    }

    private val _state = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    val state: StateFlow<WebSocketState> = _state.asStateFlow()

    private val _textMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val textMessages: SharedFlow<String> = _textMessages.asSharedFlow()

    private val _binaryMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val binaryMessages: SharedFlow<ByteArray> = _binaryMessages.asSharedFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    /**
     * Opens a WebSocket connection to the given URL.
     * No-op if already connecting or connected.
     */
    @Synchronized
    fun connect(url: String) {
        val current = _state.value
        if (current is WebSocketState.Connecting || current is WebSocketState.Connected) {
            Log.i(TAG, "connect() called in state $current, resetting connection")
            try {
                webSocket?.close(NORMAL_CLOSURE, CLOSE_REASON)
            } catch (_: Exception) {}
            webSocket = null
        }

        Log.i(TAG, "Connecting to $url")
        _state.value = WebSocketState.Connecting

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, createListener())
    }

    /**
     * Closes the WebSocket connection gracefully.
     * No-op if already disconnected.
     */
    @Synchronized
    fun disconnect() {
        val ws = webSocket ?: return
        val current = _state.value
        if (current is WebSocketState.Disconnected || current is WebSocketState.Disconnecting) {
            return
        }

        Log.i(TAG, "Disconnecting")
        _state.value = WebSocketState.Disconnecting
        ws.close(NORMAL_CLOSURE, CLOSE_REASON)
    }

    /**
     * Sends a text (JSON control) message.
     * Returns false if the connection is not in Connected state.
     */
    fun sendText(text: String): Boolean {
        val ws = webSocket ?: return false
        if (!_state.value.canSend()) return false
        return ws.send(text)
    }

    /**
     * Sends a binary message.
     * Returns false if the connection is not in Connected state.
     */
    fun sendBinary(data: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!_state.value.canSend()) return false
        return ws.send(data.toByteString())
    }

    private fun createListener() = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Connected (code=${response.code})")
            _state.value = WebSocketState.Connected
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _textMessages.tryEmit(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            _binaryMessages.tryEmit(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Server closing (code=$code, reason=$reason)")
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Closed (code=$code, reason=$reason)")
            cleanup()
            _state.value = WebSocketState.Disconnected
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Connection failed: ${t.message}", t)
            cleanup()
            _state.value = WebSocketState.Failed(
                reason = t.message ?: "Unknown error",
                code = response?.code
            )
        }
    }

    @Synchronized
    private fun cleanup() {
        webSocket = null
    }

    /** For testing: emits an incoming text message. */
    fun emitTestTextMessage(text: String): Boolean = _textMessages.tryEmit(text)
}
