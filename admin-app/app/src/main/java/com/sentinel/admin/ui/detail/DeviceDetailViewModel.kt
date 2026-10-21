package com.sentinel.admin.ui.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceContact
import com.sentinel.admin.domain.model.DeviceContactBook
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.domain.model.PlaybackState
import com.sentinel.admin.domain.repository.AudioRepository
import com.sentinel.admin.domain.repository.ContactRepository
import com.sentinel.admin.domain.repository.DeviceRepository
import com.sentinel.admin.service.AudioMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.sentinel.admin.ui.common.RefreshOperation
import com.sentinel.shared.protocol.CommandTypes
import javax.inject.Inject

/**
 * ViewModel for the Device Detail screen.
 *
 * Receives deviceId from SavedStateHandle (navigation argument).
 * Loads device from DeviceRepository (REST API).
 * Observes live WebSocket updates for the selected device.
 * Supports refresh, retry, audio listen/stop, and contact details management.
 *
 * Audio: Observes AudioMonitor.playbackState and statistics.
 * Does NOT own PlaybackState — AudioMonitor does (app-level state).
 *
 * No Android Context. No networking logic.
 */
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val audioRepository: AudioRepository,
    private val contactRepository: ContactRepository,
    private val audioMonitor: AudioMonitor,
    private val webSocketDataSource: com.sentinel.admin.data.remote.websocket.WebSocketDataSource,
    private val pttAudioRecorder: com.sentinel.admin.data.audio.PttAudioRecorder,
    private val connectionSupervisor: com.sentinel.admin.domain.supervisor.ConnectionSupervisor? = null
) : ViewModel() {

    companion object {
        private const val TAG = "Sentinel:DeviceDetail"
    }

    private val deviceId: String = savedStateHandle.get<String>("deviceId")
        ?: throw IllegalArgumentException("deviceId is required")

    private val targetServerDeviceId: String
        get() {
            val dev = _uiState.value.device
            if (dev != null) {
                if (dev.deviceId.isNotBlank() && dev.deviceId != "HOST-001") {
                    return dev.deviceId
                }
                if (dev.uniqueKey.isNotBlank() && dev.uniqueKey != "HOST-001") {
                    return dev.uniqueKey
                }
                if (dev.deviceId.isNotBlank()) {
                    return dev.deviceId
                }
            }
            return deviceId
        }

    private val audioTargetDeviceId: String
        get() {
            val dev = _uiState.value.device
            if (dev != null) {
                if (dev.deviceId.isNotBlank() && dev.deviceId != "HOST-001") {
                    return dev.deviceId
                }
                if (dev.uniqueKey.isNotBlank() && dev.uniqueKey != "HOST-001") {
                    return dev.uniqueKey
                }
                if (dev.deviceId.isNotBlank()) {
                    return dev.deviceId
                }
            }
            return deviceId
        }

    private val _uiState = MutableStateFlow(DeviceDetailUiState())
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    /** Track whether initial commands have been sent to avoid duplicates. */
    @Volatile
    private var initialCommandsSent = false

    private var userRequestedDiagnostics = false

    init {
        loadDevice()
        observeAudioState()
        observeLiveUpdates()
        observeContactBook()
        observeCommandResults()
        observeConnectionAndSendInitialCommands()
    }

    /**
     * Observes WebSocket state changes.
     * Defers the initial GET_SYSTEM_INFO / REQUEST_TELEMETRY commands until the
     * WebSocket is actually connected, and auto-retries when a reconnect occurs.
     */
    private fun observeConnectionAndSendInitialCommands() {
        // Trigger ensureConnected on launch so supervisor connects if not already active
        connectionSupervisor?.ensureConnected()

        viewModelScope.launch {
            webSocketDataSource.state.collect { wsState ->
                if (wsState is com.sentinel.admin.data.remote.websocket.WebSocketState.Connected) {
                    if (!initialCommandsSent) {
                        initialCommandsSent = true
                        sendSystemInfoCommand(showDialog = false)
                    }
                } else if (wsState is com.sentinel.admin.data.remote.websocket.WebSocketState.Disconnected ||
                           wsState is com.sentinel.admin.data.remote.websocket.WebSocketState.Failed) {
                    // Allow re-send on next reconnect
                    initialCommandsSent = false
                }
            }
        }
    }

    /**
     * Observes live WebSocket updates for this specific device.
     * Uses distinctUntilChanged to avoid unnecessary recomposition.
     *
     * NOTE: Does NOT fall back to a random online device — that would silently
     * hijack the screen to control a different device than the one the user selected.
     */
    private fun observeLiveUpdates() {
        viewModelScope.launch {
            deviceRepository.devices
                .collect { map ->
                    val resolved = map[deviceId]
                        ?: map.values.find { it.uniqueKey == deviceId || it.connectionId == deviceId }
                        ?: if (deviceId.contains("_")) {
                            val modelSuffix = deviceId.substringAfter("_")
                            val matches = map.values.filter { it.model.equals(modelSuffix, ignoreCase = true) }.distinctBy { it.uniqueKey }
                            if (matches.size == 1) matches.first() else null
                        } else null
                        ?: map.values.find { it.resolvedCallsign.equals(deviceId, ignoreCase = true) }
                        ?: map.values.find { it.deviceId == deviceId }

                    if (resolved != null) {
                        val nearestSummary = calculateNearestUnitSummary(resolved, map.values)
                        _uiState.update { st ->
                            st.copy(
                                device = resolved,
                                nearestUnitSummary = nearestSummary,
                                predictedInterceptSummary = computeKalmanKinematicIntercept(
                                    resolved.latestLocation,
                                    st.routePoints,
                                    st.compassAzimuthDeg,
                                    st.motionActivity
                                ) ?: st.predictedInterceptSummary
                            )
                        }
                    }
                }
        }
    }

    private fun calculateNearestUnitSummary(currentDev: Device, allDevices: Collection<Device>): String? {
        val myLoc = currentDev.latestLocation ?: return null
        if (!myLoc.hasReportedFix || (myLoc.latitude == 0.0 && myLoc.longitude == 0.0)) return null

        val otherUnits = allDevices
            .filter { it.uniqueKey != currentDev.uniqueKey && it.deviceId != currentDev.deviceId }
            .distinctBy { it.uniqueKey }
            .mapNotNull { other ->
                val otherLoc = other.latestLocation
                if (otherLoc != null && otherLoc.hasReportedFix && (otherLoc.latitude != 0.0 || otherLoc.longitude != 0.0)) {
                    val distKm = haversineDistanceKm(myLoc.latitude, myLoc.longitude, otherLoc.latitude, otherLoc.longitude)
                    other to distKm
                } else null
            }
            .sortedBy { it.second }

        val nearest = otherUnits.firstOrNull() ?: return null
        val (unit, distKm) = nearest
        val unitLabel = unit.resolvedCallsign.ifBlank { unit.deviceName.ifBlank { unit.model } }
        val distStr = if (distKm < 1.0) {
            "${(distKm * 1000).toInt()}m away"
        } else {
            String.format(java.util.Locale.US, "%.2f km away", distKm)
        }
        return "$unitLabel · $distStr"
    }

    private fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun computeKalmanKinematicIntercept(
        currentLoc: com.sentinel.admin.domain.model.DeviceLocation?,
        routePoints: List<com.sentinel.admin.domain.model.DeviceLocation>,
        azimuthDeg: Int?,
        motionActivity: String?
    ): String? {
        val baseLoc = currentLoc ?: routePoints.lastOrNull() ?: return null
        val lat0 = baseLoc.latitude
        val lon0 = baseLoc.longitude
        if (lat0 == 0.0 && lon0 == 0.0) return null

        val isStationary = motionActivity != null && motionActivity.contains("STATIONARY", ignoreCase = true)

        if (routePoints.size >= 2 && !isStationary) {
            val recentPoints = routePoints.takeLast(5)
            val alpha = 0.65
            val beta = 0.35

            val p0 = recentPoints[0]
            val p1 = recentPoints[1]
            val t0 = try { java.time.Instant.parse(p0.recordedAt).epochSecond } catch (_: Exception) { 0L }
            val t1 = try { java.time.Instant.parse(p1.recordedAt).epochSecond } catch (_: Exception) { 30L }
            val dt0 = (t1 - t0).coerceIn(1L, 600L).toDouble()
            var vLat = (p1.latitude - p0.latitude) / dt0
            var vLon = (p1.longitude - p0.longitude) / dt0
            var posLat = p1.latitude
            var posLon = p1.longitude

            for (i in 2 until recentPoints.size) {
                val pCurr = recentPoints[i]
                val tPrev = try { java.time.Instant.parse(recentPoints[i - 1].recordedAt).epochSecond } catch (_: Exception) { (i - 1) * 30L }
                val tCurr = try { java.time.Instant.parse(pCurr.recordedAt).epochSecond } catch (_: Exception) { i * 30L }
                val dt = (tCurr - tPrev).coerceIn(1L, 600L).toDouble()

                // Prediction step
                val predLat = posLat + vLat * dt
                val predLon = posLon + vLon * dt

                // Innovation / residual
                val rLat = pCurr.latitude - predLat
                val rLon = pCurr.longitude - predLon

                // Correction step (alpha-beta filter)
                posLat = predLat + alpha * rLat
                posLon = predLon + alpha * rLon
                vLat += (beta / dt) * rLat
                vLon += (beta / dt) * rLon
            }

            // Approximate conversion from deg/s to km/h:
            val latRad = Math.toRadians(posLat)
            val dyKmSec = vLat * 111.0
            val dxKmSec = vLon * (111.0 * kotlin.math.cos(latRad))
            val speedKmS = kotlin.math.sqrt(dxKmSec * dxKmSec + dyKmSec * dyKmSec)
            val speedKmh = speedKmS * 3600.0

            if (speedKmh >= 1.5) {
                val cogDeg = ((Math.toDegrees(kotlin.math.atan2(dxKmSec, dyKmSec)) + 360.0) % 360.0).toInt()
                val dt15mSec = 900.0 // 15 minutes
                val projLat = lat0 + vLat * dt15mSec
                val projLon = lon0 + vLon * dt15mSec
                val conf = (65 + (recentPoints.size * 6)).coerceIn(55, 96)
                return String.format(
                    java.util.Locale.US,
                    "+15m Project: (%.4f, %.4f) · Track %03d° @ %.0f km/h (%d%% Conf)",
                    projLat, projLon, cogDeg, speedKmh, conf
                )
            }
        }

        val speedKmh = when {
            motionActivity?.contains("VEHICLE", ignoreCase = true) == true -> {
                val match = Regex("""([0-9]+)""").find(motionActivity)
                match?.groupValues?.get(1)?.toDoubleOrNull() ?: 45.0
            }
            motionActivity?.contains("RUNNING", ignoreCase = true) == true -> {
                val match = Regex("""([0-9.]+)""").find(motionActivity)
                match?.groupValues?.get(1)?.toDoubleOrNull() ?: 10.0
            }
            motionActivity?.contains("WALKING", ignoreCase = true) == true -> {
                val match = Regex("""([0-9.]+)""").find(motionActivity)
                match?.groupValues?.get(1)?.toDoubleOrNull() ?: 5.0
            }
            motionActivity?.contains("MOVING", ignoreCase = true) == true -> 35.0
            else -> 0.0
        }

        if (speedKmh < 1.0 || isStationary) {
            return String.format(java.util.Locale.US, "Holding Position (%.4f, %.4f · 98%% Conf)", lat0, lon0)
        }

        val bearingDeg = azimuthDeg ?: 0
        val distKm = speedKmh * 0.25 // 15 minutes = 0.25 hours
        val latRad = Math.toRadians(lat0)
        val bearingRad = Math.toRadians(bearingDeg.toDouble())
        val dLat = (distKm * kotlin.math.cos(bearingRad)) / 111.0
        val dLon = (distKm * kotlin.math.sin(bearingRad)) / (111.0 * kotlin.math.cos(latRad))
        val projLat = lat0 + dLat
        val projLon = lon0 + dLon

        return String.format(
            java.util.Locale.US,
            "+15m Project: (%.4f, %.4f) · Heading %03d° @ %.0f km/h (72%% Conf)",
            projLat, projLon, bearingDeg, speedKmh
        )
    }

    /**
     * Observes contact book synced directly from the host device.
     */
    private fun observeContactBook() {
        viewModelScope.launch {
            contactRepository.getContactBook(deviceId).collect { book ->
                _uiState.update { it.copy(contactBook = book) }
            }
        }
    }

    // ============================================================
    // User actions
    // ============================================================

    private fun createFallbackDemoDevice(id: String): Device {
        return Device(
            deviceId = id,
            connectionId = "CONN-DEMO-${id.takeLast(4).padStart(4, '0')}",
            authenticated = true,
            registered = true,
            registrationState = "registered",
            heartbeatStatus = "online",
            connectedAt = "2026-09-28T09:00:00Z",
            lastHeartbeat = "2026-09-28T09:25:00Z",
            deviceName = "Pixel 9 Pro ($id)",
            appVersion = "1.0.0",
            model = "Google Pixel (Host)",
            latestLocation = DeviceLocation(
                deviceId = id,
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 4.2,
                battery = 92,
                network = "5G (Charging)",
                recordedAt = "2026-09-28T09:25:00Z"
            )
        )
    }

    fun loadDevice() {
        val cached = deviceRepository.devices.value[deviceId]
            ?: deviceRepository.devices.value.values.find { it.uniqueKey == deviceId || it.connectionId == deviceId }
            ?: if (deviceId.contains("_")) {
                val modelSuffix = deviceId.substringAfter("_")
                val matches = deviceRepository.devices.value.values.filter { it.model.equals(modelSuffix, ignoreCase = true) }.distinctBy { it.uniqueKey }
                if (matches.size == 1) matches.first() else null
            } else null
            ?: deviceRepository.devices.value.values.find { it.resolvedCallsign.equals(deviceId, ignoreCase = true) }
            ?: deviceRepository.devices.value.values.find { it.deviceId == deviceId }
        if (cached != null) {
            val nearest = calculateNearestUnitSummary(cached, deviceRepository.devices.value.values)
            _uiState.update { it.copy(device = cached, isLoading = false, errorMessage = null, nearestUnitSummary = nearest) }
            loadRouteHistory()
        } else {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            loadRouteHistory()
        }
        viewModelScope.launch {
            deviceRepository.getDevice(deviceId)
                .onSuccess { device ->
                    val nearest = calculateNearestUnitSummary(device, deviceRepository.devices.value.values)
                    _uiState.update {
                        it.copy(
                            device = device,
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = null,
                            nearestUnitSummary = nearest
                        )
                    }
                    loadRouteHistory()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = if (cached != null) null else (error.message ?: "Failed to load device")
                        )
                    }
                }
        }
    }

    fun loadRouteHistory(startDate: String = "", endDate: String = "") {
        viewModelScope.launch {
            val start = startDate.ifBlank { null }
            val end = endDate.ifBlank { null }
            deviceRepository.getRouteHistory(targetServerDeviceId, start, end)
                .onSuccess { points ->
                    _uiState.update { st ->
                        st.copy(
                            routePoints = points,
                            predictedInterceptSummary = computeKalmanKinematicIntercept(
                                st.device?.latestLocation,
                                points,
                                st.compassAzimuthDeg,
                                st.motionActivity
                            ) ?: st.predictedInterceptSummary
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load route history for $targetServerDeviceId: ${error.message}")
                }

            deviceRepository.getBatteryHistory(targetServerDeviceId)
                .onSuccess { batt ->
                    if (batt.isNotEmpty()) {
                        _uiState.update { it.copy(batteryHistory = batt) }
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load battery history for $targetServerDeviceId: ${error.message}")
                }
        }
    }

    fun deployCustomGeofence(lat: Double, lon: Double, radiusMeters: Float) {
        val params = org.json.JSONObject().apply {
            put("id", "custom_perimeter_${System.currentTimeMillis() / 1000}")
            put("latitude", lat)
            put("longitude", lon)
            put("radius", radiusMeters)
        }
        _uiState.update { it.copy(commandStatusMessage = "Deploying ${radiusMeters.toInt()}m perimeter to Host...") }
        sendCommand("SET_GEOFENCE", params)
    }

    private var refreshJob: kotlinx.coroutines.Job? = null
    private var settlingJob: kotlinx.coroutines.Job? = null
    private var nextRefreshAttemptId: Long = 0L

    fun refresh() {
        if (_uiState.value.refreshOperation is RefreshOperation.Running) {
            return
        }
        settlingJob?.cancel()
        val attemptId = ++nextRefreshAttemptId
        _uiState.update {
            it.copy(
                refreshOperation = RefreshOperation.Running(attemptId),
                isRefreshing = true,
                errorMessage = null
            )
        }
        refreshJob = viewModelScope.launch {
            try {
                val device = deviceRepository.fetchFreshDevice(deviceId).getOrThrow()
                currentCoroutineContext().ensureActive()
                completeSuccessIfRunning(attemptId, device)
            } catch (cancelled: CancellationException) {
                completeCancelledIfRunning(attemptId)
                throw cancelled
            } catch (failure: Exception) {
                completeErrorIfRunning(attemptId, failure)
            } finally {
                clearUnfinishedRunningAttemptIfOwned(attemptId)
            }
        }
    }

    private fun completeSuccessIfRunning(attemptId: Long, device: Device) {
        _uiState.update { current ->
            if (current.refreshOperation is RefreshOperation.Running && current.refreshOperation.attemptId == attemptId) {
                current.copy(
                    device = device,
                    isRefreshing = false,
                    errorMessage = null,
                    refreshOperation = RefreshOperation.Success(attemptId)
                )
            } else current
        }
        scheduleSettling(attemptId)
    }

    private fun completeErrorIfRunning(attemptId: Long, error: Throwable) {
        val errorMsg = error.message ?: "Failed to refresh"
        _uiState.update { current ->
            if (current.refreshOperation is RefreshOperation.Running && current.refreshOperation.attemptId == attemptId) {
                current.copy(
                    isRefreshing = false,
                    errorMessage = errorMsg,
                    refreshOperation = RefreshOperation.Error(attemptId, errorMsg)
                )
            } else current
        }
        scheduleSettling(attemptId)
    }

    private fun completeCancelledIfRunning(attemptId: Long) {
        _uiState.update { current ->
            if (current.refreshOperation is RefreshOperation.Running && current.refreshOperation.attemptId == attemptId) {
                current.copy(
                    isRefreshing = false,
                    refreshOperation = RefreshOperation.Cancelled(attemptId)
                )
            } else current
        }
    }

    private fun clearUnfinishedRunningAttemptIfOwned(attemptId: Long) {
        _uiState.update { current ->
            if (current.refreshOperation is RefreshOperation.Running && current.refreshOperation.attemptId == attemptId) {
                current.copy(
                    isRefreshing = false,
                    refreshOperation = RefreshOperation.Idle
                )
            } else current
        }
    }

    private fun scheduleSettling(attemptId: Long) {
        settlingJob?.cancel()
        settlingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1800)
            _uiState.update { current ->
                val op = current.refreshOperation
                if ((op is RefreshOperation.Success && op.attemptId == attemptId) ||
                    (op is RefreshOperation.Error && op.attemptId == attemptId) ||
                    (op is RefreshOperation.Cancelled && op.attemptId == attemptId)
                ) {
                    current.copy(refreshOperation = RefreshOperation.Idle)
                } else current
            }
        }
    }

    fun consumeRefreshOutcome(attemptId: Long) {
        _uiState.update { current ->
            val op = current.refreshOperation
            if (op.attemptId == attemptId && (op is RefreshOperation.Success || op is RefreshOperation.Error || op is RefreshOperation.Cancelled)) {
                settlingJob?.cancel()
                current.copy(refreshOperation = RefreshOperation.Idle)
            } else {
                current
            }
        }
    }

    fun retry() {
        loadDevice()
    }

    // ============================================================
    // Audio actions
    // ============================================================

    fun onListenClick() {
        val target = audioTargetDeviceId
        val streamId = java.util.UUID.randomUUID()
        // Send LISTEN with the streamId so the host starts streaming with exactly this UUID.
        // Do NOT send START_AUDIO_STREAM separately — it would make the host generate a new
        // random UUID, causing a streamId mismatch and dropping all AUDIO_V2 frames on the server.
        audioRepository.listen(target, streamId)
        audioMonitor.start(target, streamId)
    }

    fun onStopClick() {
        if (audioMonitor.isRecording.value) {
            audioMonitor.stopRecording()
        }
        val target = audioTargetDeviceId
        val currentStream = audioMonitor.activeStreamId
        // Send STOP (not STOP_AUDIO_STREAM command) so the host releases the mic cleanly.
        audioRepository.stopListening(target, currentStream)
        audioMonitor.stop()
    }

    fun toggleRecording(context: android.content.Context) {
        if (audioMonitor.isRecording.value) {
            audioMonitor.stopRecording()
        } else {
            val recordingsDir = java.io.File(context.getExternalFilesDir(null), "Recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val targetFile = java.io.File(recordingsDir, "REC_${deviceId}_$timestamp.wav")
            audioMonitor.startRecording(targetFile)
        }
    }

    // ============================================================
    // Audio observation
    // ============================================================

    private fun observeAudioState() {
        viewModelScope.launch {
            audioMonitor.playbackState.collect { state ->
                _uiState.update { it.copy(playbackState = state) }
            }
        }
        viewModelScope.launch {
            audioMonitor.statistics.collect { stats ->
                _uiState.update { it.copy(audioStats = stats) }
            }
        }
        viewModelScope.launch {
            audioMonitor.isRecording.collect { isRec ->
                _uiState.update { it.copy(isRecording = isRec) }
            }
        }
        viewModelScope.launch {
            audioMonitor.recordingDurationMs.collect { duration ->
                _uiState.update { it.copy(recordingDurationMs = duration) }
            }
        }
        viewModelScope.launch {
            audioMonitor.incomingAudioLevel.collect { level ->
                _uiState.update { it.copy(incomingAudioLevel = level) }
            }
        }
        viewModelScope.launch {
            audioMonitor.incomingAudioDbFs.collect { dbFs ->
                _uiState.update { it.copy(incomingAudioDbFs = dbFs) }
            }
        }
        viewModelScope.launch {
            audioMonitor.incomingAudioFreqHz.collect { freqHz ->
                _uiState.update { it.copy(incomingAudioFreqHz = freqHz) }
            }
        }
        viewModelScope.launch {
            audioMonitor.incomingAcousticClass.collect { acousticClass ->
                _uiState.update { it.copy(incomingAcousticClass = acousticClass) }
            }
        }
    }

    // ============================================================
    // Air Commands Execution & Listening
    // ============================================================

    fun sendSystemInfoCommand(showDialog: Boolean = true) {
        userRequestedDiagnostics = showDialog
        if (showDialog && _uiState.value.diagnosticsData.isNotEmpty()) {
            _uiState.update { it.copy(showDiagnosticsDialog = true) }
        }
        val model = _uiState.value.device?.model ?: if (deviceId.contains("_") && !deviceId.substringAfter("_").startsWith("HOST", ignoreCase = true)) deviceId.substringAfter("_") else ""
        val params = org.json.JSONObject().apply {
            if (model.isNotBlank()) put("targetModel", model)
            put("targetUniqueKey", deviceId)
        }
        sendCommand("GET_SYSTEM_INFO", params)
        sendCommand("REQUEST_TELEMETRY", params)
    }

    fun sendTriggerBeaconCommand() {
        sendCommand("TRIGGER_BEACON")
    }

    fun sendCapturePhotoCommand(useFront: Boolean = false) {
        val params = org.json.JSONObject().apply { put("front", useFront) }
        sendCommand("CAPTURE_PHOTO", params)
    }

    fun sendFetchLogsCommand() {
        sendCommand("FETCH_SMS_LOGS")
    }

    fun sendFetchAppLogsCommand(fullDevice: Boolean = false, filter: String = "") {
        _uiState.update { it.copy(isFetchingAppLogs = true) }
        val params = org.json.JSONObject().apply {
            put("fullDevice", fullDevice)
            if (filter.isNotBlank()) {
                put("filter", filter)
            }
        }
        sendCommand("FETCH_APP_LOGS", params)
    }

    fun clearAppLogs() {
        _uiState.update { it.copy(hostAppLogs = emptyList()) }
    }

    fun sendFetchNotificationLogsCommand() {
        sendCommand("FETCH_NOTIFICATION_LOGS")
    }

    fun sendExecuteShellCommand(commandText: String) {
        val params = org.json.JSONObject().apply { put("cmd", commandText) }
        sendCommand("EXECUTE_SHELL", params)
    }

    fun armPerimeterGeofence(radiusMeters: Float = 100f) {
        val loc = _uiState.value.device?.latestLocation
        if (loc == null) {
            _uiState.update { it.copy(commandStatusMessage = "Cannot arm geofence: No GPS coordinates yet.") }
            return
        }
        val params = org.json.JSONObject().apply {
            put("id", "perimeter_guard")
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("radius", radiusMeters)
        }
        _uiState.update { it.copy(commandStatusMessage = "Arming 100m Perimeter Guard on Host...") }
        sendCommand("SET_GEOFENCE", params)
    }


    fun openAddressBookDialog() {
        _uiState.update { it.copy(showAddressBookDialog = true) }
    }

    fun dismissAddressBookDialog() {
        _uiState.update { it.copy(showAddressBookDialog = false) }
    }

    fun dismissDialogs() {
        userRequestedDiagnostics = false
        _uiState.update {
            it.copy(
                showDiagnosticsDialog = false,
                showPhotoDialog = false,
                showShellDialog = false,
                showLogsDialog = false,
                showNotifLogsDialog = false,
                showAddressBookDialog = false,
                showScreenshotDialog = false,
                showPreviewDialog = false,
                showCallLogsDialog = false,
                showInstalledAppsDialog = false,
                commandStatusMessage = null
            )
        }
    }

    fun openCallLogsDialog() {
        _uiState.update { it.copy(showCallLogsDialog = true) }
        val params = org.json.JSONObject().apply {
            put("limit", 100)
            put("offset", 0)
        }
        sendCommand(com.sentinel.shared.protocol.CommandTypes.FETCH_CALL_LOGS, params)
    }

    fun dismissCallLogsDialog() {
        _uiState.update { it.copy(showCallLogsDialog = false) }
    }

    fun openInstalledAppsDialog() {
        _uiState.update { it.copy(showInstalledAppsDialog = true) }
        val params = org.json.JSONObject().apply {
            put("includeSystem", true)
            put("limit", 300)
            put("offset", 0)
        }
        sendCommand(com.sentinel.shared.protocol.CommandTypes.FETCH_INSTALLED_APPS, params)
    }

    fun dismissInstalledAppsDialog() {
        _uiState.update { it.copy(showInstalledAppsDialog = false) }
    }

    private fun sendCommand(command: String, params: org.json.JSONObject = org.json.JSONObject()) {
        val model = _uiState.value.device?.model ?: if (deviceId.contains("_") && !deviceId.substringAfter("_").startsWith("HOST", ignoreCase = true)) deviceId.substringAfter("_") else ""
        if (model.isNotBlank() && !params.has("targetModel")) {
            params.put("targetModel", model)
        }
        if (!params.has("targetUniqueKey")) {
            params.put("targetUniqueKey", deviceId)
        }

        val commandJson = org.json.JSONObject().apply {
            put("type", "COMMAND")
            put("version", 1)
            put("timestamp", System.currentTimeMillis() / 1000)
            put("sequence", System.currentTimeMillis())

            val data = org.json.JSONObject().apply {
                put("targetDeviceId", targetServerDeviceId)
                put("command", command)
                put("params", params)
            }
            put("data", data)
        }

        android.util.Log.i("Sentinel:AdminCmd", "Sending COMMAND $command to target $targetServerDeviceId (key=$deviceId)")
        val payloadText = try { commandJson.toString() } catch (_: Exception) { "{}" } ?: "{}"
        val sent = webSocketDataSource.sendText(payloadText)
        android.util.Log.i("Sentinel:AdminCmd", "sendText returned: $sent (wsState=${webSocketDataSource.state.value})")

        if (!sent) {
            connectionSupervisor?.ensureConnected()
            if (_uiState.value.commandStatusMessage == null) {
                _uiState.update { it.copy(commandStatusMessage = "Connecting to server... Re-attempting.") }
            }
        }
    }

    private fun observeCommandResults() {
        viewModelScope.launch {
            webSocketDataSource.textMessages.collect { rawText ->
                try {
                    android.util.Log.i("Sentinel:AdminCmd", "WS incoming text: $rawText")
                    val json = org.json.JSONObject(rawText)
                    val msgType = json.optString("type")

                    if (msgType == "ERROR") {
                        val errData = json.optJSONObject("data")
                        val code = errData?.optInt("code", 0) ?: 0
                        val msg = errData?.optString("message", "Unknown error") ?: "Unknown error"
                        android.util.Log.e("Sentinel:AdminCmd", "Server error received: code=$code, msg=$msg")
                        _uiState.update { it.copy(commandStatusMessage = "Server Error ($code): $msg") }
                        if (code == 404 || msg.contains("offline", ignoreCase = true) || msg.contains("not found", ignoreCase = true)) {
                            audioMonitor.stop()
                        }
                        return@collect
                    }

                    if (msgType == "COMMAND") {
                        // Ignore server-reflected COMMAND broadcasts to eliminate infinite retry loops
                        android.util.Log.d("Sentinel:AdminCmd", "Ignoring reflected COMMAND broadcast")
                        return@collect
                    }

                    if (msgType != "COMMAND_RESULT") return@collect

                    val data = json.optJSONObject("data") ?: return@collect
                    val command = data.optString("command")
                    val success = data.optBoolean("success", false)
                    val payload = data.optJSONObject("payload") ?: org.json.JSONObject()

                    // Isolate results so actions from other devices do not cross-contaminate this detail screen
                    val resModel = payload.optString("model", data.optString("model", ""))
                    val resUniqueKey = payload.optString("uniqueKey", data.optString("uniqueKey", ""))
                    val resDeviceId = payload.optString("deviceId", data.optString("deviceId", ""))
                    val resCallsign = payload.optString("callsign", data.optString("callsign", ""))
                    val resHardwareId = payload.optString("hardwareId", data.optString("hardwareId", ""))

                    val currentDev = _uiState.value.device
                    val myModel = currentDev?.model ?: if (deviceId.contains("_") && !deviceId.substringAfter("_").startsWith("HOST", ignoreCase = true)) deviceId.substringAfter("_") else ""
                    val myKey = currentDev?.uniqueKey ?: deviceId
                    val myDeviceId = currentDev?.deviceId ?: if (deviceId.contains("_")) deviceId.substringBefore("_") else deviceId
                    val myCallsign = currentDev?.resolvedCallsign?.ifBlank { currentDev.callsign }.orEmpty()
                    val myHardwareId = currentDev?.hardwareId.orEmpty()

                    if (resModel.isNotBlank() && myModel.isNotBlank() && !resModel.equals(myModel, ignoreCase = true)) {
                        android.util.Log.d("Sentinel:AdminCmd", "Filtered out $command result from other model '$resModel' (screen is for '$myModel')")
                        return@collect
                    }

                    if (resDeviceId.isNotBlank() && myDeviceId.isNotBlank() && resDeviceId != "HOST-001" && myDeviceId != "HOST-001" && !resDeviceId.equals(myDeviceId, ignoreCase = true)) {
                        android.util.Log.d("Sentinel:AdminCmd", "Filtered out $command result from other hardware device '$resDeviceId' (screen is for '$myDeviceId')")
                        return@collect
                    }

                    val isKeyMatch = when {
                        resUniqueKey.isBlank() || myKey.isBlank() -> true
                        resUniqueKey == myKey -> true
                        myKey.contains(resUniqueKey) || resUniqueKey.contains(myKey) -> true
                        resDeviceId.isNotBlank() && (resDeviceId == myDeviceId || resDeviceId == myKey || myKey.contains(resDeviceId)) -> true
                        myCallsign.isNotBlank() && (resUniqueKey.contains(myCallsign, ignoreCase = true) || resCallsign.equals(myCallsign, ignoreCase = true)) -> true
                        resCallsign.isNotBlank() && myKey.contains(resCallsign, ignoreCase = true) -> true
                        myHardwareId.isNotBlank() && (resUniqueKey.contains(myHardwareId, ignoreCase = true) || resHardwareId.equals(myHardwareId, ignoreCase = true)) -> true
                        resHardwareId.isNotBlank() && myKey.contains(resHardwareId, ignoreCase = true) -> true
                        // Flexible match on model + base ID: e.g. legacy myKey "HOST-001_I2401" and resUniqueKey "HOST-001-VIVO-2D2C_..."
                        resModel.isNotBlank() && myModel.isNotBlank() && resModel.equals(myModel, ignoreCase = true) -> {
                            val myBase = myKey.substringBefore("_")
                            val resBase = resUniqueKey.substringBefore("_")
                            myBase == resBase || resBase.startsWith(myBase) || myBase.startsWith(resBase) ||
                                (myBase == "HOST-001" && resBase.startsWith("HOST-001")) ||
                                (resBase == "HOST-001" && myBase.startsWith("HOST-001"))
                        }
                        else -> {
                            val myBase = myKey.substringBefore("_")
                            val resBase = resUniqueKey.substringBefore("_")
                            myBase == resBase ||
                                (myBase == "HOST-001" && resBase.startsWith("HOST-001")) ||
                                (resBase == "HOST-001" && myBase.startsWith("HOST-001"))
                        }
                    }

                    if (!isKeyMatch) {
                        android.util.Log.d("Sentinel:AdminCmd", "Filtered out $command result from other device '$resUniqueKey' (screen is for '$myKey')")
                        return@collect
                    }

                    if (!success) {
                        val errorMsg = data.optString("error", "Unknown error")
                        _uiState.update {
                            it.copy(
                                isLockingDevice = false,
                                mdmActionMessage = if (command == "LOCK_DEVICE" || command == "SET_ANTI_TAMPER" || command == "ENFORCE_PERMISSIONS") {
                                    errorMsg
                                } else {
                                    it.mdmActionMessage
                                },
                                commandStatusMessage = "Command failed: $errorMsg"
                            )
                        }
                        return@collect
                    }

                    when (command) {
                        "GET_SYSTEM_INFO", "REQUEST_TELEMETRY", "TELEMETRY_REPORT" -> {
                            val map = mutableMapOf<String, Any>()
                            val iterator = payload.keys()
                            while (iterator.hasNext()) {
                                val key = iterator.next()
                                map[key] = payload.get(key)
                            }
                            val sigDbm = (payload.opt("signalDbm") as? Number)?.toInt()
                                ?: (map["signalDbm"] as? Number)?.toInt()
                            val accG = (payload.opt("accelerationG") as? Number)?.toFloat()
                                ?: (map["accelerationG"] as? Number)?.toFloat()
                            val altM = (payload.opt("estimatedAltitudeMeters") as? Number)?.toDouble()
                                ?: (map["estimatedAltitudeMeters"] as? Number)?.toDouble()
                            val floor = (payload.opt("estimatedFloor") as? Number)?.toInt()
                                ?: (map["estimatedFloor"] as? Number)?.toInt()
                            val azimuth = (payload.opt("compassAzimuthDeg") as? Number)?.toInt()
                                ?: (map["compassAzimuthDeg"] as? Number)?.toInt()
                            val motion = (payload.opt("motionActivity") as? String)
                                ?: (map["motionActivity"] as? String)
                            val explicitGnssSpoof = if (payload.has("gnssSpoofSuspected")) {
                                payload.optBoolean("gnssSpoofSuspected")
                            } else if (map.containsKey("gnssSpoofSuspected")) {
                                map["gnssSpoofSuspected"] == true
                            } else null

                            val explicitRogueCell = if (payload.has("rogueCellSuspected")) {
                                payload.optBoolean("rogueCellSuspected")
                            } else if (map.containsKey("rogueCellSuspected")) {
                                map["rogueCellSuspected"] == true
                            } else null
                            val servingCell = (payload.opt("servingCellSummary") as? String)
                                ?: (map["servingCellSummary"] as? String)

                            val devicePose = (payload.opt("devicePoseAndCameraHint") as? String)
                                ?: (map["devicePoseAndCameraHint"] as? String)
                            val ambientEnv = (payload.opt("ambientEnvironment") as? String)
                                ?: (map["ambientEnvironment"] as? String)
                            val magUt = (payload.opt("magneticFieldUt") as? Number)?.toInt()
                                ?: (map["magneticFieldUt"] as? Number)?.toInt()
                            val emfAnomaly = if (payload.has("emfAnomalyDetected")) {
                                payload.optBoolean("emfAnomalyDetected")
                            } else if (map.containsKey("emfAnomalyDetected")) {
                                map["emfAnomalyDetected"] == true
                            } else null
                            val seismic = (payload.opt("seismicVibrationSummary") as? String)
                                ?: (map["seismicVibrationSummary"] as? String)
                            val gnssConst = (payload.opt("gnssConstellationSummary") as? String)
                                ?: (map["gnssConstellationSummary"] as? String)
                            val battEndurance = (payload.opt("batteryEnduranceSummary") as? String)
                                ?: (map["batteryEnduranceSummary"] as? String)
                            val cpuFreq = (payload.opt("cpuFrequencySummary") as? String)
                                ?: (map["cpuFrequencySummary"] as? String)
                            val vpn = if (payload.has("vpnActive")) {
                                payload.optBoolean("vpnActive")
                            } else if (map.containsKey("vpnActive")) {
                                map["vpnActive"] == true
                            } else null
                            val proxy = if (payload.has("proxyActive")) {
                                payload.optBoolean("proxyActive")
                            } else if (map.containsKey("proxyActive")) {
                                map["proxyActive"] == true
                            } else null
                            val traffic = (payload.opt("totalTrafficSummary") as? String)
                                ?: (map["totalTrafficSummary"] as? String)
                            val tcpSockets = (payload.opt("activeTcpSockets") as? Number)?.toInt()
                                ?: (map["activeTcpSockets"] as? Number)?.toInt()

                            val defconLvl = (payload.opt("defconLevel") as? Number)?.toInt()
                                ?: (map["defconLevel"] as? Number)?.toInt()
                            val defconSum = (payload.opt("defconSummary") as? String)
                                ?: (map["defconSummary"] as? String)
                            val mlStatus = (payload.opt("mlModelStatus") as? String)
                                ?: (map["mlModelStatus"] as? String)
                            val usbHwSum = (payload.opt("usbHardwareSummary") as? String)
                                ?: (map["usbHardwareSummary"] as? String)
                            val usbIntrusion = if (payload.has("usbIntrusionSuspected")) {
                                payload.optBoolean("usbIntrusionSuspected")
                            } else if (map.containsKey("usbIntrusionSuspected")) {
                                map["usbIntrusionSuspected"] == true
                            } else null
                            val simSecSum = (payload.opt("simSecuritySummary") as? String)
                                ?: (map["simSecuritySummary"] as? String)
                            val simTamperAlert = if (payload.has("simTamperOrRoamingAlert")) {
                                payload.optBoolean("simTamperOrRoamingAlert")
                            } else if (map.containsKey("simTamperOrRoamingAlert")) {
                                map["simTamperOrRoamingAlert"] == true
                            } else null
                            val vertMotion = (payload.opt("verticalMotionState") as? String)
                                ?: (map["verticalMotionState"] as? String)

                            // Admin-Side Independent Bayesian DEFCON Verifier
                            val isRooted = (payload.opt("isRooted") as? Boolean)
                                ?: (map["isRooted"] as? Boolean) ?: false
                            val isSelinuxEnforced = (payload.opt("isSelinuxEnforced") as? Boolean)
                                ?: (map["isSelinuxEnforced"] as? Boolean) ?: true
                            val isUsbAdbActive = (payload.opt("isUsbAdbActive") as? Boolean)
                                ?: (map["isUsbAdbActive"] as? Boolean) ?: false
                            val isAdbEnabled = (payload.opt("isAdbEnabled") as? Boolean)
                                ?: (map["isAdbEnabled"] as? Boolean) ?: false
                            val isDevOpts = (payload.opt("isDeveloperOptionsEnabled") as? Boolean)
                                ?: (map["isDeveloperOptionsEnabled"] as? Boolean) ?: false

                            var adminLogOdds = -3.8
                            val adminRiskDrivers = mutableListOf<String>()

                            if (isRooted) { adminLogOdds += 5.6; adminRiskDrivers.add("Root/Kernel Tamper") }
                            if (usbIntrusion == true) { adminLogOdds += 5.3; adminRiskDrivers.add("USB HID/Locked Port Intrusion") }
                            if (!isSelinuxEnforced) { adminLogOdds += 4.3; adminRiskDrivers.add("SELinux Permissive") }
                            if (explicitGnssSpoof == true) { adminLogOdds += 4.1; adminRiskDrivers.add("GNSS Mock/Spoof") }
                            if (explicitRogueCell == true) { adminLogOdds += 4.2; adminRiskDrivers.add("Rogue 2G Cell/IMSI") }
                            if (simTamperAlert == true) { adminLogOdds += 2.8; adminRiskDrivers.add("SIM Roaming/Mismatch") }
                            if (isUsbAdbActive) { adminLogOdds += 2.7; adminRiskDrivers.add("Active USB ADB") }
                            else if (isAdbEnabled || isDevOpts) { adminLogOdds += 1.3; adminRiskDrivers.add("ADB/DevOpts Enabled") }
                            if (vpn == true || proxy == true) { adminLogOdds += 2.5; adminRiskDrivers.add("VPN/Proxy Tunnel") }
                            if (emfAnomaly == true) { adminLogOdds += 1.4; adminRiskDrivers.add("High EMF (>120µT)") }

                            val adminProb = (1.0 / (1.0 + kotlin.math.exp(-adminLogOdds))) * 100.0
                            val adminProbPct = (kotlin.math.round(adminProb * 10.0) / 10.0).coerceIn(0.1, 99.9)
                            val adminVerifiedDefcon = when {
                                adminProbPct >= 80.0 -> 1
                                adminProbPct >= 50.0 -> 2
                                adminProbPct >= 20.0 -> 3
                                adminProbPct >= 6.0 -> 4
                                else -> 5
                            }
                            val finalDefcon = minOf(defconLvl ?: 5, adminVerifiedDefcon)
                            val finalDefconSummary = if (!defconSum.isNullOrBlank() && (defconLvl ?: 5) <= adminVerifiedDefcon) {
                                defconSum
                            } else {
                                val driverText = if (adminRiskDrivers.isNotEmpty()) adminRiskDrivers.take(2).joinToString(" + ") else "All Sensors Nominal"
                                String.format(java.util.Locale.US, "DEFCON %d (%.1f%% Risk · %s)", finalDefcon, adminProbPct, driverText)
                            }

                            _uiState.update {
                                val effMotion = motion ?: it.motionActivity
                                val effAzimuth = azimuth ?: it.compassAzimuthDeg
                                val intercept = computeKalmanKinematicIntercept(
                                    it.device?.latestLocation,
                                    it.routePoints,
                                    effAzimuth,
                                    effMotion
                                )

                                it.copy(
                                    showDiagnosticsDialog = if (userRequestedDiagnostics && command == "GET_SYSTEM_INFO") true else it.showDiagnosticsDialog,
                                    diagnosticsData = if (command == "GET_SYSTEM_INFO") map else if (it.diagnosticsData.isEmpty()) map else it.diagnosticsData,
                                    signalDbm = sigDbm ?: it.signalDbm,
                                    accelerationG = accG ?: it.accelerationG,
                                    estimatedAltitudeMeters = altM ?: it.estimatedAltitudeMeters,
                                    estimatedFloor = floor ?: it.estimatedFloor,
                                    compassAzimuthDeg = azimuth ?: it.compassAzimuthDeg,
                                    motionActivity = motion ?: it.motionActivity,
                                    gnssSpoofSuspected = explicitGnssSpoof ?: it.gnssSpoofSuspected,
                                    rogueCellSuspected = explicitRogueCell ?: it.rogueCellSuspected,
                                    servingCellSummary = servingCell ?: it.servingCellSummary,
                                    devicePoseAndCameraHint = devicePose ?: it.devicePoseAndCameraHint,
                                    ambientEnvironment = ambientEnv ?: it.ambientEnvironment,
                                    magneticFieldUt = magUt ?: it.magneticFieldUt,
                                    emfAnomalyDetected = emfAnomaly ?: it.emfAnomalyDetected,
                                    seismicVibrationSummary = seismic ?: it.seismicVibrationSummary,
                                    gnssConstellationSummary = gnssConst ?: it.gnssConstellationSummary,
                                    batteryEnduranceSummary = battEndurance ?: it.batteryEnduranceSummary,
                                    cpuFrequencySummary = cpuFreq ?: it.cpuFrequencySummary,
                                    vpnActive = vpn ?: it.vpnActive,
                                    proxyActive = proxy ?: it.proxyActive,
                                    totalTrafficSummary = traffic ?: it.totalTrafficSummary,
                                    activeTcpSockets = tcpSockets ?: it.activeTcpSockets,
                                    defconLevel = finalDefcon,
                                    defconSummary = finalDefconSummary,
                                    mlModelStatus = mlStatus ?: it.mlModelStatus,
                                    usbHardwareSummary = usbHwSum ?: it.usbHardwareSummary,
                                    usbIntrusionSuspected = usbIntrusion ?: it.usbIntrusionSuspected,
                                    simSecuritySummary = simSecSum ?: it.simSecuritySummary,
                                    simTamperOrRoamingAlert = simTamperAlert ?: it.simTamperOrRoamingAlert,
                                    verticalMotionState = vertMotion ?: it.verticalMotionState,
                                    predictedInterceptSummary = intercept ?: it.predictedInterceptSummary
                                )
                            }
                        }

                        "CAPTURE_PHOTO" -> {
                            val imageBase64 = payload.optString("imageBase64")
                            val facing = payload.optString("cameraFacing", "REAR")
                            val newMediaItems = if (imageBase64.isNotBlank()) {
                                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                                val newItem = MediaItem(
                                    id = "capture-${System.currentTimeMillis()}-${imageBase64.hashCode()}",
                                    timeLabel = "Captured $timeStr",
                                    cameraFacing = if (facing.equals("FRONT", ignoreCase = true)) "Front camera" else "Rear camera",
                                    base64Data = imageBase64
                                )
                                (listOf(newItem) + _uiState.value.sessionMediaItems).distinctBy { m -> m.id }.take(20)
                            } else {
                                _uiState.value.sessionMediaItems
                            }
                            _uiState.update {
                                it.copy(
                                    showPhotoDialog = true,
                                    capturedPhotoBase64 = imageBase64,
                                    capturedPhotoFacing = facing,
                                    sessionMediaItems = newMediaItems
                                )
                            }
                        }

                        "EXECUTE_SHELL" -> {
                            val output = payload.optString("output", "No output")
                            _uiState.update {
                                it.copy(showShellDialog = true, shellOutput = output)
                            }
                        }

                        "FETCH_SMS_LOGS" -> {
                            val jsonArray = payload.optJSONArray("logs")
                            val logs = mutableListOf<String>()
                            if (jsonArray != null) {
                                for (i in 0 until jsonArray.length()) {
                                    logs.add(jsonArray.getString(i))
                                }
                            }
                            if (logs.isEmpty()) {
                                logs.add("[SMS_INBOX] No SMS messages retrieved or inbox empty.")
                            }
                            _uiState.update {
                                it.copy(showLogsDialog = true, logsList = logs)
                            }
                        }

                        "FETCH_APP_LOGS" -> {
                            val jsonArray = payload.optJSONArray("appLogs")
                            val list = mutableListOf<String>()
                            if (jsonArray != null) {
                                for (i in 0 until jsonArray.length()) {
                                    list.add(jsonArray.getString(i))
                                }
                            }
                            _uiState.update {
                                it.copy(
                                    hostAppLogs = if (list.isNotEmpty()) list else it.hostAppLogs,
                                    isFetchingAppLogs = false
                                )
                            }
                        }

                        "SET_GEOFENCE" -> {
                            val set = payload.optBoolean("geofenceSet", false)
                            _uiState.update {
                                it.copy(commandStatusMessage = if (set) "Perimeter Guard Armed (100m) successfully!" else "Geofence update failed")
                            }
                        }




                        "TRIGGER_BEACON" -> {
                            _uiState.update {
                                it.copy(commandStatusMessage = "Beacon triggered on Host device successfully!")
                            }
                        }

                        "FETCH_CALL_LOGS" -> {
                            val jsonArray = payload.optJSONArray("callLogs")
                            val list = mutableListOf<Map<String, Any>>()
                            if (jsonArray != null) {
                                for (i in 0 until jsonArray.length()) {
                                    val obj = jsonArray.optJSONObject(i) ?: continue
                                    val map = mutableMapOf<String, Any>()
                                    val keys = obj.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        map[k] = obj.get(k)
                                    }
                                    list.add(map)
                                }
                            }
                            _uiState.update {
                                it.copy(callLogsList = list, showCallLogsDialog = true)
                            }
                        }

                        "FETCH_INSTALLED_APPS" -> {
                            val jsonArray = payload.optJSONArray("apps")
                            val list = mutableListOf<Map<String, Any>>()
                            if (jsonArray != null) {
                                for (i in 0 until jsonArray.length()) {
                                    val obj = jsonArray.optJSONObject(i) ?: continue
                                    val map = mutableMapOf<String, Any>()
                                    val keys = obj.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        map[k] = obj.get(k)
                                    }
                                    list.add(map)
                                }
                            }
                            _uiState.update {
                                it.copy(installedAppsList = list, showInstalledAppsDialog = true)
                            }
                        }

                        "FETCH_NOTIFICATION_LOGS" -> {
                            val rawNotifJson = payload.optString("notificationLogs", "[]")
                            _uiState.update {
                                it.copy(showNotifLogsDialog = true, notifLogsJsonRaw = rawNotifJson)
                            }
                        }

                        "FETCH_CONTACTS" -> {
                            _uiState.update { it.copy(isSyncingContacts = false) }
                            val contactsJson = payload.optJSONArray("contacts")
                            val emergencyJson = payload.optJSONObject("emergencyContact")
                            val error = payload.optString("error", "")

                            if (error.isNotBlank() && (contactsJson == null || contactsJson.length() == 0)) {
                                _uiState.update {
                                    it.copy(commandStatusMessage = "Device Contacts: $error")
                                }
                                return@collect
                            }

                            val contactList = mutableListOf<DeviceContact>()
                            if (contactsJson != null) {
                                for (i in 0 until contactsJson.length()) {
                                    val obj = contactsJson.optJSONObject(i)
                                    if (obj != null) {
                                        val name = obj.optString("name", "Unknown Contact")
                                        val phone = obj.optString("phone", "")
                                        val type = obj.optString("type", "Mobile")
                                        val isEmergency = obj.optBoolean("isEmergency", false) || obj.optString("isEmergency") == "true"
                                        contactList.add(
                                            DeviceContact(
                                                name = name,
                                                phone = phone,
                                                type = type,
                                                isEmergency = isEmergency
                                            )
                                        )
                                    }
                                }
                            }

                            val emergencyContact = if (emergencyJson != null) {
                                DeviceContact(
                                    name = emergencyJson.optString("name", "Emergency Contact"),
                                    phone = emergencyJson.optString("phone", ""),
                                    type = emergencyJson.optString("type", "Emergency"),
                                    isEmergency = true
                                )
                            } else {
                                contactList.firstOrNull { it.isEmergency }
                            }

                            val timestamp = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.US).format(java.util.Date())
                            val contactBook = DeviceContactBook(
                                deviceId = deviceId,
                                total = contactList.size,
                                contacts = contactList,
                                emergencyContact = emergencyContact,
                                lastSynced = timestamp
                            )

                            viewModelScope.launch {
                                contactRepository.saveContactBook(deviceId, contactBook)
                                _uiState.update {
                                    it.copy(
                                        contactBook = contactBook,
                                        commandStatusMessage = "Successfully synced ${contactList.size} contact(s) from device!"
                                    )
                                }
                            }
                        }

                        "CAPTURE_SCREENSHOT" -> {
                            val map = mutableMapOf<String, Any?>()
                            val iterator = payload.keys()
                            while (iterator.hasNext()) {
                                val key = iterator.next()
                                map[key] = payload.get(key)
                            }
                            _uiState.update {
                                it.copy(showScreenshotDialog = true, screenshotPayload = map)
                            }
                        }

                        "PREVIEW_FILE" -> {
                            val map = mutableMapOf<String, Any?>()
                            val iterator = payload.keys()
                            while (iterator.hasNext()) {
                                val key = iterator.next()
                                map[key] = payload.get(key)
                            }
                            _uiState.update {
                                it.copy(showPreviewDialog = true, previewPayload = map)
                            }
                        }

                        "LOCK_DEVICE" -> {
                            val locked = payload.optBoolean("locked", false)
                            val method = payload.optString("method", "MDM")
                            _uiState.update {
                                it.copy(
                                    isLockingDevice = false,
                                    mdmActionMessage = if (locked) "Target device locked remotely ($method)" else "Failed to lock device"
                                )
                            }
                        }

                        "SET_ANTI_TAMPER" -> {
                            val enabled = payload.optBoolean("antiTamperEnabled", false)
                            val success = payload.optBoolean("success", false)
                            _uiState.update {
                                it.copy(
                                    isAntiTamperEnabled = if (success) enabled else it.isAntiTamperEnabled,
                                    mdmActionMessage = if (success) "Uninstall protection ${if (enabled) "enabled" else "disabled"}" else "Failed to update uninstall protection"
                                )
                            }
                        }

                        "ENFORCE_PERMISSIONS" -> {
                            val enforced = payload.optBoolean("permissionsEnforced", false)
                            _uiState.update {
                                it.copy(
                                    mdmActionMessage = if (enforced) "All runtime permissions auto-granted successfully" else "Failed to auto-grant (requires Device Owner mode)"
                                )
                            }
                        }

                        "GET_MDM_STATUS" -> {
                            val isAdmin = payload.optBoolean("isDeviceAdminActive", false)
                            val isOwner = payload.optBoolean("isDeviceOwner", false)
                            _uiState.update {
                                it.copy(isDeviceAdminActive = isAdmin, isDeviceOwner = isOwner)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Sentinel:AdminCmd", "Failed to parse COMMAND_RESULT: ${e.message}", e)
                }
            }
        }
    }

    fun sendCaptureScreenshotCommand(maxWidth: Int = 1080, quality: Int = 80) {
        _uiState.update { it.copy(commandStatusMessage = "Capturing remote screenshot...") }
        val params = org.json.JSONObject().apply {
            put("maxWidth", maxWidth)
            put("quality", quality)
        }
        sendCommand("CAPTURE_SCREENSHOT", params)
    }

    fun sendPreviewFileCommand(path: String, maxDim: Int = 720, textLines: Int = 250) {
        _uiState.update { it.copy(commandStatusMessage = "Requesting remote file preview...") }
        val params = org.json.JSONObject().apply {
            put("path", path)
            put("maxDim", maxDim)
            put("textLines", textLines)
        }
        sendCommand("PREVIEW_FILE", params)
    }

    fun sendSyncContactsCommand(limit: Int = 0) {
        _uiState.update { it.copy(isSyncingContacts = true, commandStatusMessage = "Syncing contacts from host device...") }
        val params = org.json.JSONObject().apply {
            put("limit", limit)
        }
        sendCommand("FETCH_CONTACTS", params)
    }

    fun setPttArmed(armed: Boolean) {
        if (!armed && _uiState.value.isPttTransmitting) {
            stopPtt()
        }
        _uiState.update { it.copy(isPttArmed = armed) }
    }

    fun startPtt() {
        if (!_uiState.value.isPttArmed) {
            android.util.Log.w("Sentinel:AdminPtt", "PTT is disarmed / locked. Arm safety switch to speak.")
            return
        }
        if (_uiState.value.isPttTransmitting) return

        _uiState.update { it.copy(isPttTransmitting = true, pttAudioLevel = 0f) }
        sendCommand("PTT_START")

        val started = pttAudioRecorder.start { base64Chunk, level ->
            _uiState.update { it.copy(pttAudioLevel = level) }
            val params = org.json.JSONObject().apply {
                put("pcmBase64", base64Chunk)
            }
            sendCommand("PTT_AUDIO", params)
        }

        if (!started) {
            _uiState.update {
                it.copy(
                    isPttTransmitting = false,
                    pttAudioLevel = 0f,
                    commandStatusMessage = "Failed to access microphone for PTT"
                )
            }
            sendCommand("PTT_STOP")
        }
    }

    fun stopPtt() {
        if (!_uiState.value.isPttTransmitting && !pttAudioRecorder.isRecording) return
        pttAudioRecorder.stop()
        _uiState.update { it.copy(isPttTransmitting = false, pttAudioLevel = 0f) }
        sendCommand("PTT_STOP")
    }

    fun lockDevice() {
        _uiState.update { it.copy(isLockingDevice = true, mdmActionMessage = "Sending remote lock command...") }
        sendCommand(CommandTypes.LOCK_DEVICE)
        viewModelScope.launch {
            kotlinx.coroutines.delay(8_000L)
            if (_uiState.value.isLockingDevice) {
                _uiState.update {
                    it.copy(
                        isLockingDevice = false,
                        mdmActionMessage = "Lock command timed out — device may be offline or busy"
                    )
                }
            }
        }
    }

    fun setAntiTamper(enabled: Boolean) {
        _uiState.update { it.copy(mdmActionMessage = "Updating uninstall protection...") }
        val params = org.json.JSONObject().apply {
            put("enabled", enabled)
        }
        sendCommand(CommandTypes.SET_ANTI_TAMPER, params)
    }

    fun enforcePermissions() {
        _uiState.update { it.copy(mdmActionMessage = "Enforcing runtime permissions...") }
        sendCommand(CommandTypes.ENFORCE_PERMISSIONS)
    }

    fun fetchMdmStatus() {
        sendCommand(CommandTypes.GET_MDM_STATUS)
    }

    fun testFallDetection(impactG: Float = 4.8f) {
        val params = org.json.JSONObject().apply {
            put("impactG", impactG.toDouble())
        }
        sendCommand("TEST_FALL_ALERT", params)
    }

    fun setFallDetection(enabled: Boolean, soundAlarm: Boolean = false) {
        val params = org.json.JSONObject().apply {
            put("enabled", enabled)
            put("soundAlarm", soundAlarm)
        }
        sendCommand("SET_FALL_DETECTION", params)
    }

    fun dismissMdmMessage() {
        _uiState.update { it.copy(mdmActionMessage = null) }
    }

    fun wakeDevice() {
        if (_uiState.value.isWakingDevice) return
        _uiState.update { it.copy(isWakingDevice = true, wakeStatusMessage = "Sending High-Priority FCM Wakeup...") }
        viewModelScope.launch {
            val result = deviceRepository.wakeDevice(deviceId)
            if (result.isSuccess) {
                _uiState.update { it.copy(isWakingDevice = false, wakeStatusMessage = "FCM Wakeup dispatched to Google Play Services!") }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Wakeup failed"
                _uiState.update { it.copy(isWakingDevice = false, wakeStatusMessage = "Wake error: $err") }
            }
            kotlinx.coroutines.delay(4_000L)
            _uiState.update { it.copy(wakeStatusMessage = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pttAudioRecorder.stop()
        if (audioMonitor.playbackState.value !is PlaybackState.Idle &&
            audioMonitor.playbackState.value !is PlaybackState.Stopped) {
            onStopClick()
        }
    }
}
