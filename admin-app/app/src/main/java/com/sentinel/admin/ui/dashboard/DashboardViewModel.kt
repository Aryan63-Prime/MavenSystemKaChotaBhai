package com.sentinel.admin.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.sentinel.admin.ui.common.RefreshOperation
import javax.inject.Inject

/**
 * ViewModel for the Dashboard screen.
 *
 * Responsibilities:
 * - Loads devices from DeviceRepository (REST)
 * - Supports manual and pull-to-refresh
 * - Client-side search (name, ID, model)
 * - Sorting (status, alpha, battery, heartbeat)
 * - Exposes immutable [DashboardUiState]
 *
 * No Android Context. No networking logic. No WebSocket.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val connectionSupervisor: com.sentinel.admin.domain.supervisor.ConnectionSupervisor? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        connectionSupervisor?.ensureConnected()
        loadDevices()
        observeLiveUpdates()
        observeEmergencyAlerts()
    }

    /**
     * Observes the repository's live device map for WebSocket updates.
     * Only updates the list — does not trigger loading/refresh states.
     */
    private fun observeLiveUpdates() {
        viewModelScope.launch {
            deviceRepository.devices.collect { deviceMap ->
                if (deviceMap.isNotEmpty()) {
                    val deduplicated = deviceMap.values
                        .sortedWith(compareByDescending<Device> { it.heartbeatStatus == "online" }.thenByDescending { it.lastHeartbeat })
                        .distinctBy { it.uniqueKey }
                    _uiState.update {
                        it.copy(devices = deduplicated)
                    }
                    applyFilterAndSort()
                }
            }
        }
    }

    private fun observeEmergencyAlerts() {
        viewModelScope.launch {
            deviceRepository.emergencyAlert.collect { alert ->
                _uiState.update { it.copy(emergencyAlert = alert) }
                applyFilterAndSort()
            }
        }
    }

    fun dismissEmergencyAlert() {
        deviceRepository.dismissEmergencyAlert()
        applyFilterAndSort()
    }

    // ============================================================
    // User actions
    // ============================================================

    private var refreshJob: kotlinx.coroutines.Job? = null
    private var settlingJob: kotlinx.coroutines.Job? = null
    private var nextRefreshAttemptId: Long = 0L

    /**
     * Initial load or manual toolbar refresh.
     */
    fun loadDevices() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            deviceRepository.getDevices()
                .onSuccess { devices ->
                    val deduplicated = devices
                        .sortedWith(compareByDescending<Device> { it.heartbeatStatus == "online" }.thenByDescending { it.lastHeartbeat })
                        .distinctBy { it.uniqueKey }
                    _uiState.update {
                        it.copy(
                            devices = deduplicated,
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = null
                        )
                    }
                    applyFilterAndSort()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.message ?: "Unknown error"
                        )
                    }
                }
        }
    }

    /**
     * Authoritative pull-to-refresh action with attempt tracking and synchronous in-flight guard.
     */
    fun refresh() {
        if (_uiState.value.refreshOperation is RefreshOperation.Running) {
            return
        }
        settlingJob?.cancel()
        val attemptId = ++nextRefreshAttemptId
        connectionSupervisor?.ensureConnected()
        _uiState.update {
            it.copy(
                refreshOperation = RefreshOperation.Running(attemptId),
                isRefreshing = true,
                errorMessage = null
            )
        }
        refreshJob = viewModelScope.launch {
            try {
                val devices = deviceRepository.getDevices().getOrThrow()
                currentCoroutineContext().ensureActive()
                val deduplicated = devices
                    .sortedWith(compareByDescending<Device> { it.heartbeatStatus == "online" }.thenByDescending { it.lastHeartbeat })
                    .distinctBy { it.uniqueKey }
                _uiState.update { current ->
                    if (current.refreshOperation is RefreshOperation.Running && current.refreshOperation.attemptId == attemptId) {
                        current.copy(
                            devices = deduplicated,
                            isRefreshing = false,
                            errorMessage = null,
                            refreshOperation = RefreshOperation.Success(attemptId)
                        )
                    } else current
                }
                applyFilterAndSort()
                scheduleSettling(attemptId)
            } catch (cancelled: CancellationException) {
                _uiState.update { current ->
                    if (current.refreshOperation is RefreshOperation.Running && current.refreshOperation.attemptId == attemptId) {
                        current.copy(
                            isRefreshing = false,
                            refreshOperation = RefreshOperation.Cancelled(attemptId)
                        )
                    } else current
                }
                throw cancelled
            } catch (failure: Exception) {
                val errorMsg = failure.message ?: "Failed to refresh devices"
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
            } finally {
                _uiState.update { current ->
                    if (current.refreshOperation is RefreshOperation.Running && current.refreshOperation.attemptId == attemptId) {
                        current.copy(
                            isRefreshing = false,
                            refreshOperation = RefreshOperation.Idle
                        )
                    } else current
                }
            }
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

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilterAndSort()
    }

    fun onSortOrderChanged(sortOrder: SortOrder) {
        _uiState.update { it.copy(sortOrder = sortOrder) }
        applyFilterAndSort()
    }

    fun onFleetFilterChanged(fleetFilter: FleetFilter) {
        _uiState.update { it.copy(fleetFilter = fleetFilter) }
        applyFilterAndSort()
    }

    fun onViewModeChanged(viewMode: ViewMode) {
        _uiState.update { it.copy(viewMode = viewMode) }
    }

    fun retry() {
        loadDevices()
    }

    // ============================================================
    // Internal
    // ============================================================

    internal fun applyFilterAndSort() {
        val state = _uiState.value
        val searchFiltered = filterDevices(state.devices, state.searchQuery)
        val fleetFiltered = filterByFleet(searchFiltered, state.fleetFilter)
        val sorted = sortDevices(fleetFiltered, state.sortOrder)
        val markers = buildMarkers(sorted)
        _uiState.update { it.copy(displayDevices = sorted, markers = markers) }
    }

    internal fun filterByFleet(devices: List<Device>, filter: FleetFilter): List<Device> {
        val alertKey = _uiState.value.emergencyAlert?.deviceId
        return when (filter) {
            FleetFilter.ALL -> devices
            FleetFilter.ONLINE -> devices.filter { it.heartbeatStatus == "online" }
            FleetFilter.LOW_BATTERY -> devices.filter {
                val b = it.latestLocation?.battery ?: -1
                b in 0..20
            }
            FleetFilter.EMERGENCY -> devices.filter {
                alertKey != null && (it.deviceId == alertKey || it.uniqueKey == alertKey || it.connectionId == alertKey)
            }
        }
    }

    internal fun filterDevices(devices: List<Device>, query: String): List<Device> {
        if (query.isBlank()) return devices
        val q = query.trim().lowercase()
        return devices.filter { device ->
            device.deviceName.lowercase().contains(q) ||
                    device.deviceId.lowercase().contains(q) ||
                    device.callsign.lowercase().contains(q) ||
                    device.model.lowercase().contains(q)
        }
    }

    internal fun sortDevices(devices: List<Device>, sortOrder: SortOrder): List<Device> {
        return when (sortOrder) {
            SortOrder.STATUS_THEN_NAME -> devices.sortedWith(
                compareByDescending<Device> { it.heartbeatStatus == "online" }
                    .thenBy { it.deviceName.lowercase() }
            )
            SortOrder.ALPHABETICAL -> devices.sortedBy { it.deviceName.lowercase() }
            SortOrder.BATTERY -> devices.sortedByDescending {
                it.latestLocation?.battery ?: -1
            }
            SortOrder.LAST_HEARTBEAT -> devices.sortedByDescending {
                it.lastHeartbeat
            }
        }
    }

    internal fun buildMarkers(devices: List<Device>): List<DeviceMarker> {
        val alertKey = _uiState.value.emergencyAlert?.deviceId
        return devices.mapNotNull { device ->
            val loc = device.latestLocation
            if (loc != null && loc.hasReportedFix && com.sentinel.admin.ui.map.isValidCoordinate(loc.latitude, loc.longitude)) {
                val isEmergency = alertKey != null && (device.deviceId == alertKey || device.uniqueKey == alertKey || device.connectionId == alertKey)
                DeviceMarker(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    isOnline = device.heartbeatStatus == "online",
                    battery = loc.battery,
                    network = loc.network,
                    isMoving = false,
                    isEmergency = isEmergency,
                    uniqueKey = device.uniqueKey
                )
            } else null
        }
    }
}
