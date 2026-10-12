package com.sentinel.admin.ui.dashboard

import com.sentinel.admin.domain.model.Device

/**
 * Immutable UI state for the Dashboard screen.
 *
 * Produced by [DashboardViewModel], consumed by [DashboardScreen].
 */
data class DashboardUiState(
    /** All devices from the API (unfiltered). */
    val devices: List<Device> = emptyList(),
    /** Filtered and sorted devices displayed in the list. */
    val displayDevices: List<Device> = emptyList(),
    /** Map markers for devices with locations. */
    val markers: List<DeviceMarker> = emptyList(),
    /** Current view mode (List or Map). */
    val viewMode: ViewMode = ViewMode.LIST,
    /** Current search query. */
    val searchQuery: String = "",
    /** Current sort order. */
    val sortOrder: SortOrder = SortOrder.STATUS_THEN_NAME,
    /** Fleet filtering mode (All, Online, Low Battery, Alerts). */
    val fleetFilter: FleetFilter = FleetFilter.ALL,
    /** Whether devices are loading. */
    val isLoading: Boolean = false,
    /** Whether a refresh is in progress. */
    val isRefreshing: Boolean = false,
    /** Error message, null if no error. */
    val errorMessage: String? = null,
    /** Active emergency alert (e.g. fall detected), or null if none. */
    val emergencyAlert: com.sentinel.admin.domain.model.EmergencyAlert? = null,
    /** Authoritative refresh operation lifecycle state. */
    val refreshOperation: com.sentinel.admin.ui.common.RefreshOperation = com.sentinel.admin.ui.common.RefreshOperation.Idle
) {
    /** True if no devices after loading completes. */
    val isEmpty: Boolean
        get() = !isLoading && devices.isEmpty() && errorMessage == null

    /** True if there are devices but search returned no results. */
    val isSearchEmpty: Boolean
        get() = !isLoading && devices.isNotEmpty() && displayDevices.isEmpty()

    /** Human-readable explanation when filtered results are empty. */
    val emptyFilterMessage: String?
        get() = when {
            searchQuery.isNotBlank() -> "No results for \"$searchQuery\""
            fleetFilter != FleetFilter.ALL -> "No devices match filter: ${fleetFilter.label}"
            else -> null
        }
}

/**
 * Dashboard view modes.
 */
enum class ViewMode {
    LIST, MAP
}

/**
 * Sort orders for the device list.
 */
enum class SortOrder(val label: String) {
    STATUS_THEN_NAME("Status"),
    ALPHABETICAL("A-Z"),
    BATTERY("Battery"),
    LAST_HEARTBEAT("Heartbeat")
}

/**
 * Fleet filter tags for multi-device dashboard and map.
 */
enum class FleetFilter(val label: String) {
    ALL("All"),
    ONLINE("Online"),
    LOW_BATTERY("Low Battery"),
    EMERGENCY("Alerts")
}
