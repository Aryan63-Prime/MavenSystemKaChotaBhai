package com.sentinel.admin.ui.files

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentinel.admin.data.remote.protocol.MessageSerializer
import com.sentinel.admin.domain.model.ConnectionEvent
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.domain.model.FileItem
import com.sentinel.admin.domain.repository.ConnectionRepository
import com.sentinel.admin.service.files.FileDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FileViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val messageSerializer: MessageSerializer,
    private val downloadManager: FileDownloadManager,
    private val webSocketDataSource: com.sentinel.admin.data.remote.websocket.WebSocketDataSource
) : ViewModel() {

    private val TAG = "Sentinel:FileVM"

    private val _currentPath = MutableStateFlow("/storage/emulated/0")
    val currentPath = _currentPath.asStateFlow()

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _previewPayload = MutableStateFlow<Map<String, Any?>?>(null)
    val previewPayload = _previewPayload.asStateFlow()

    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading = _isPreviewLoading.asStateFlow()

    private var pendingLoadDeviceId: String? = null

    val downloadState = downloadManager.state

    init {
        // Observe connection state
        connectionRepository.state
            .onEach { state ->
                Log.d(TAG, "Connection state changed: $state")
                if (state is ConnectionState.Ready || state is ConnectionState.TransportConnected) {
                    val deviceId = pendingLoadDeviceId
                    if (deviceId != null && _files.value.isEmpty() && !_isLoading.value) {
                        Log.i(TAG, "Connection ready, executing pending load for $deviceId")
                        loadDirectory(deviceId, _currentPath.value)
                    }
                }
            }
            .launchIn(viewModelScope)

        // Observe connection events
        connectionRepository.events
            .onEach { event ->
                Log.d(TAG, "Event received: ${event::class.simpleName}")
                when (event) {
                    is ConnectionEvent.FilesListReceived -> {
                        val incoming = messageSerializer.deserialize(event.rawJson)
                        if (incoming is com.sentinel.admin.data.remote.protocol.IncomingMessage.FilesListRes) {
                            Log.i(TAG, "Received file list for ${incoming.path} with ${incoming.items.size} items")
                            _files.value = incoming.items.map { 
                                FileItem(it.name, it.is_dir, it.size, it.last_modified)
                            }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                            _isLoading.value = false
                            pendingLoadDeviceId = null
                        }
                    }
                    is ConnectionEvent.ServerError -> {
                        Log.e(TAG, "Server error: ${event.message}")
                        _isLoading.value = false
                    }
                    else -> {}
                }
            }
            .launchIn(viewModelScope)

        // Observe WebSocket COMMAND_RESULT for PREVIEW_FILE
        viewModelScope.launch {
            webSocketDataSource.textMessages.collect { rawText ->
                try {
                    val json = org.json.JSONObject(rawText)
                    if (json.optString("type") == "COMMAND_RESULT") {
                        val data = json.optJSONObject("data") ?: return@collect
                        if (data.optString("command") == "PREVIEW_FILE") {
                            val resDeviceId = data.optString("deviceId", "")
                            if (pendingLoadDeviceId != null && resDeviceId.isNotBlank() && resDeviceId != "HOST-001" && !pendingLoadDeviceId!!.contains(resDeviceId.removePrefix("HOST-001-"))) {
                                return@collect
                            }
                            _isPreviewLoading.value = false
                            if (data.optBoolean("success", false)) {
                                val payload = data.optJSONObject("payload")
                                if (payload != null) {
                                    val map = mutableMapOf<String, Any?>()
                                    val it = payload.keys()
                                    while (it.hasNext()) {
                                        val key = it.next()
                                        map[key] = payload.get(key)
                                    }
                                    _previewPayload.value = map
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun requestPreview(deviceId: String, item: FileItem) {
        pendingLoadDeviceId = deviceId
        val path = if (_currentPath.value.endsWith("/")) "${_currentPath.value}${item.name}" else "${_currentPath.value}/${item.name}"
        _isPreviewLoading.value = true
        val cmd = org.json.JSONObject().apply {
            put("type", "COMMAND")
            put("version", 1)
            put("timestamp", System.currentTimeMillis() / 1000)
            put("sequence", System.currentTimeMillis())
            val data = org.json.JSONObject().apply {
                put("targetDeviceId", deviceId)
                put("command", "PREVIEW_FILE")
                put("params", org.json.JSONObject().apply {
                    put("path", path)
                    put("maxDim", 720)
                    put("textLines", 250)
                })
            }
            put("data", data)
        }
        connectionRepository.sendText(cmd.toString())
    }

    fun dismissPreview() {
        _previewPayload.value = null
    }

    fun loadDirectory(deviceId: String, path: String) {
        Log.i(TAG, "Loading directory: $path for device: $deviceId")
        _currentPath.value = path
        _isLoading.value = true
        pendingLoadDeviceId = deviceId
        
        val req = messageSerializer.serializeFilesListReq(deviceId, path, System.currentTimeMillis())
        val sent = connectionRepository.sendText(req)
        
        if (sent) {
            Log.d(TAG, "Request sent successfully")
        } else {
            Log.w(TAG, "Failed to send request (disconnected?), will retry when ready")
        }
    }

    fun navigateBack(deviceId: String) {
        val current = _currentPath.value
        if (current == "/" || current == "/storage/emulated/0") return
        
        val parent = current.substringBeforeLast("/", "/")
        loadDirectory(deviceId, if (parent.isEmpty()) "/" else parent)
    }

    fun downloadFile(deviceId: String, item: FileItem) {
        val path = if (_currentPath.value.endsWith("/")) "${_currentPath.value}${item.name}" else "${_currentPath.value}/${item.name}"
        downloadManager.startDownload(deviceId, path)
    }

    fun cancelDownload(deviceId: String) {
        downloadManager.cancelDownload()
        val req = messageSerializer.serializeFileStopReq(deviceId, _currentPath.value, System.currentTimeMillis())
        connectionRepository.sendText(req)
    }
}
