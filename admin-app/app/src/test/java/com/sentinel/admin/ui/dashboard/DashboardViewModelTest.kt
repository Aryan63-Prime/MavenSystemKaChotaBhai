package com.sentinel.admin.ui.dashboard

import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.domain.model.DeviceUpdateEvent
import com.sentinel.admin.domain.model.EventStatistics
import com.sentinel.admin.domain.repository.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DashboardViewModel].
 *
 * Covers:
 * - Loading state
 * - Success with devices
 * - Empty state
 * - Error state
 * - Refresh
 * - Retry
 * - Search filtering
 * - Sort orders
 * - UI state computed properties
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    // ============================================================
    // Test doubles
    // ============================================================

    private class FakeDeviceRepository : DeviceRepository {
        var devicesResult: Result<List<Device>> = Result.success(emptyList())
        var deviceResult: Result<Device> = Result.failure(NoSuchElementException())
        var callCount = 0

        override val devices: StateFlow<Map<String, Device>> = MutableStateFlow(emptyMap())
        override val deviceUpdates: SharedFlow<DeviceUpdateEvent> = MutableSharedFlow()
        override val eventStatistics: StateFlow<EventStatistics> = MutableStateFlow(EventStatistics())
        override val emergencyAlert: StateFlow<com.sentinel.admin.domain.model.EmergencyAlert?> = MutableStateFlow(null)

        override fun dismissEmergencyAlert() {}
        override fun triggerTestEmergencyAlert(alert: com.sentinel.admin.domain.model.EmergencyAlert) {}
        override suspend fun wakeDevice(deviceId: String): Result<Boolean> = Result.success(true)

        override suspend fun getDevices(): Result<List<Device>> {
            callCount++
            return devicesResult
        }

        override suspend fun getDevice(deviceId: String): Result<Device> {
            return deviceResult
        }

        override suspend fun fetchFreshDevice(deviceId: String): Result<Device> {
            return deviceResult
        }
    }

    // ============================================================
    // Setup
    // ============================================================

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeDeviceRepository
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeDeviceRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DashboardViewModel {
        return DashboardViewModel(fakeRepo)
    }

    // ============================================================
    // Loading
    // ============================================================

    @Test
    fun `initial state is loading`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()

        // Before advancing — still loading
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `loading completes with devices`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(3, viewModel.uiState.value.devices.size)
        assertEquals(3, viewModel.uiState.value.displayDevices.size)
    }

    // ============================================================
    // Success
    // ============================================================

    @Test
    fun `devices are sorted by status then name by default`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val names = viewModel.uiState.value.displayDevices.map { it.deviceName }
        // Online first, then alphabetical within each group
        assertEquals("Galaxy S24", names[0]) // online
        assertEquals("Pixel 9", names[1])    // online
        assertEquals("OnePlus 12", names[2]) // offline
    }

    @Test
    fun `error message is null on success`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
    }

    // ============================================================
    // Empty
    // ============================================================

    @Test
    fun `isEmpty true when no devices`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `isEmpty false when devices exist`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isEmpty)
    }

    // ============================================================
    // Error
    // ============================================================

    @Test
    fun `error state shows message`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.failure(RuntimeException("Network error"))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Network error", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `isEmpty false when error`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.failure(RuntimeException("fail"))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isEmpty)
    }

    // ============================================================
    // Refresh
    // ============================================================

    @Test
    fun `refresh sets isRefreshing`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isRefreshing)

        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `refresh updates devices`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES.take(1))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.devices.size)

        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.devices.size)
    }

    @Test
    fun `refresh transitions through Running to Success and scoped settling`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refresh()
        val op = viewModel.uiState.value.refreshOperation
        assertTrue("refreshOperation should be Running", op is com.sentinel.admin.ui.common.RefreshOperation.Running)
        val attemptId = op.attemptId
        assertNotNull(attemptId)

        // Concurrent duplicate refresh call while Running is rejected by synchronous guard
        viewModel.refresh()
        assertEquals("Duplicate call must not mutate attempt ID", attemptId, viewModel.uiState.value.refreshOperation.attemptId)

        // Advance dispatcher until repository call completes
        testDispatcher.scheduler.runCurrent()
        val successOp = viewModel.uiState.value.refreshOperation
        assertTrue("refreshOperation should be Success", successOp is com.sentinel.admin.ui.common.RefreshOperation.Success)
        assertEquals(attemptId, successOp.attemptId)

        // After settling time (1800ms), state transitions to Idle
        testDispatcher.scheduler.advanceTimeBy(1900)
        assertTrue("refreshOperation should be Idle after settling", viewModel.uiState.value.refreshOperation is com.sentinel.admin.ui.common.RefreshOperation.Idle)
    }

    @Test
    fun `consumeRefreshOutcome immediately resets outcome if attempt ID matches`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refresh()
        testDispatcher.scheduler.runCurrent()
        val attemptId = viewModel.uiState.value.refreshOperation.attemptId!!

        // Consuming mismatched attempt ID is ignored
        viewModel.consumeRefreshOutcome(attemptId + 999)
        assertTrue(viewModel.uiState.value.refreshOperation is com.sentinel.admin.ui.common.RefreshOperation.Success)

        // Consuming matching attempt ID resets to Idle immediately
        viewModel.consumeRefreshOutcome(attemptId)
        assertTrue(viewModel.uiState.value.refreshOperation is com.sentinel.admin.ui.common.RefreshOperation.Idle)
    }

    // ============================================================
    // Retry
    // ============================================================

    @Test
    fun `retry after error reloads`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.failure(RuntimeException("fail"))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("fail", viewModel.uiState.value.errorMessage)

        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel.retry()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(3, viewModel.uiState.value.devices.size)
    }

    // ============================================================
    // Search
    // ============================================================

    @Test
    fun `search by device name`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChanged("pixel")
        assertEquals(1, viewModel.uiState.value.displayDevices.size)
        assertEquals("Pixel 9", viewModel.uiState.value.displayDevices[0].deviceName)
    }

    @Test
    fun `search by device ID`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChanged("HOST-0002")
        assertEquals(1, viewModel.uiState.value.displayDevices.size)
        assertEquals("OnePlus 12", viewModel.uiState.value.displayDevices[0].deviceName)
    }

    @Test
    fun `search by model`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChanged("Samsung")
        assertEquals(1, viewModel.uiState.value.displayDevices.size)
    }

    @Test
    fun `search case insensitive`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChanged("PIXEL")
        assertEquals(1, viewModel.uiState.value.displayDevices.size)
    }

    @Test
    fun `search no results`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChanged("nonexistent")
        assertEquals(0, viewModel.uiState.value.displayDevices.size)
        assertTrue(viewModel.uiState.value.isSearchEmpty)
    }

    @Test
    fun `clear search restores all devices`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChanged("pixel")
        assertEquals(1, viewModel.uiState.value.displayDevices.size)

        viewModel.onSearchQueryChanged("")
        assertEquals(3, viewModel.uiState.value.displayDevices.size)
    }

    // ============================================================
    // Sorting
    // ============================================================

    @Test
    fun `sort alphabetical`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSortOrderChanged(SortOrder.ALPHABETICAL)
        val names = viewModel.uiState.value.displayDevices.map { it.deviceName }
        assertEquals(listOf("Galaxy S24", "OnePlus 12", "Pixel 9"), names)
    }

    @Test
    fun `sort by battery descending`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSortOrderChanged(SortOrder.BATTERY)
        val batteries = viewModel.uiState.value.displayDevices.map {
            it.latestLocation?.battery ?: -1
        }
        // 81, 45, -1 (null location)
        assertEquals(listOf(81, 45, -1), batteries)
    }

    @Test
    fun `sort by last heartbeat descending`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSortOrderChanged(SortOrder.LAST_HEARTBEAT)
        val heartbeats = viewModel.uiState.value.displayDevices.map { it.lastHeartbeat }
        // Descending by ISO string
        assertTrue(heartbeats[0] >= heartbeats[1])
    }

    // ============================================================
    // UI State computed properties
    // ============================================================

    @Test
    fun `isEmpty false during loading`() {
        val state = DashboardUiState(isLoading = true, devices = emptyList())
        assertFalse(state.isEmpty)
    }

    @Test
    fun `isEmpty false when error present`() {
        val state = DashboardUiState(devices = emptyList(), errorMessage = "error")
        assertFalse(state.isEmpty)
    }

    @Test
    fun `isSearchEmpty when devices exist but filtered empty`() {
        val state = DashboardUiState(
            devices = TEST_DEVICES,
            displayDevices = emptyList()
        )
        assertTrue(state.isSearchEmpty)
    }

    @Test
    fun `isSearchEmpty false when no devices at all`() {
        val state = DashboardUiState(devices = emptyList(), displayDevices = emptyList())
        assertFalse(state.isSearchEmpty)
    }

    // ============================================================
    // Filter/Sort pure functions
    // ============================================================

    @Test
    fun `filterDevices with blank query returns all`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val result = viewModel.filterDevices(TEST_DEVICES, "")
        assertEquals(3, result.size)
    }

    @Test
    fun `filterDevices with query filters correctly`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val result = viewModel.filterDevices(TEST_DEVICES, "galaxy")
        assertEquals(1, result.size)
        assertEquals("Galaxy S24", result[0].deviceName)
    }

    @Test
    fun `sortDevices status then name`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val sorted = viewModel.sortDevices(TEST_DEVICES, SortOrder.STATUS_THEN_NAME)
        // Online first (Galaxy, Pixel), then offline (OnePlus)
        assertEquals("Galaxy S24", sorted[0].deviceName)
        assertEquals("Pixel 9", sorted[1].deviceName)
        assertEquals("OnePlus 12", sorted[2].deviceName)
    }

    // ============================================================
    // Map markers
    // ============================================================

    @Test
    fun `buildMarkers only includes devices with location`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val markers = viewModel.buildMarkers(TEST_DEVICES)
        // OnePlus has null location, so only 2 markers
        assertEquals(2, markers.size)
    }

    @Test
    fun `buildMarkers maps fields correctly`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val markers = viewModel.buildMarkers(TEST_DEVICES)
        val pixel = markers.find { it.deviceId == "HOST-0001" }!!
        assertEquals("Pixel 9", pixel.deviceName)
        assertEquals(28.6139, pixel.latitude, 0.0001)
        assertEquals(77.2090, pixel.longitude, 0.0001)
        assertTrue(pixel.isOnline)
        assertEquals(81, pixel.battery)
        assertEquals("5G", pixel.network)
    }

    @Test
    fun `buildMarkers offline device has isOnline false`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val offlineDevice = TEST_DEVICES[1].copy(
            latestLocation = DeviceLocation(
                deviceId = "HOST-0002", latitude = 0.0, longitude = 0.0,
                accuracy = 10.0, battery = 50, network = "WiFi",
                recordedAt = "2026-07-09T11:00:00Z"
            )
        )
        val markers = viewModel.buildMarkers(listOf(offlineDevice))
        assertFalse(markers[0].isOnline)
    }

    @Test
    fun `buildMarkers empty list returns empty`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val markers = viewModel.buildMarkers(emptyList())
        assertEquals(0, markers.size)
    }

    @Test
    fun `markers populated after load`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // 2 devices have location, 1 does not
        assertEquals(2, viewModel.uiState.value.markers.size)
    }

    @Test
    fun `markers snippet format`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val marker = DeviceMarker(
            deviceId = "HOST-0001", deviceName = "Test",
            latitude = 0.0, longitude = 0.0,
            isOnline = true, battery = 81, network = "5G"
        )
        assertEquals("HOST-0001 · 81% · 5G", marker.snippet)
    }

    // ============================================================
    // View mode toggle
    // ============================================================

    @Test
    fun `default view mode is LIST`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ViewMode.LIST, viewModel.uiState.value.viewMode)
    }

    @Test
    fun `toggle to MAP mode`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(emptyList())
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onViewModeChanged(ViewMode.MAP)
        assertEquals(ViewMode.MAP, viewModel.uiState.value.viewMode)

        viewModel.onViewModeChanged(ViewMode.LIST)
        assertEquals(ViewMode.LIST, viewModel.uiState.value.viewMode)
    }

    // ============================================================
    // Fleet Filter
    // ============================================================

    @Test
    fun `fleet filter ONLINE filters out offline devices`() = runTest(testDispatcher) {
        fakeRepo.devicesResult = Result.success(TEST_DEVICES)
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFleetFilterChanged(FleetFilter.ONLINE)

        // HOST-0001 and HOST-0003 are online, HOST-0002 is offline
        val onlineDevices = viewModel.uiState.value.displayDevices
        assertEquals(2, onlineDevices.size)
        assertTrue(onlineDevices.any { it.deviceId == "HOST-0001" })
        assertTrue(onlineDevices.any { it.deviceId == "HOST-0003" })
        assertFalse(onlineDevices.any { it.deviceId == "HOST-0002" })
    }

    @Test
    fun `fleet filter LOW_BATTERY filters devices with battery less than or equal to 20`() = runTest(testDispatcher) {
        val lowBattDevice = TEST_DEVICES[0].copy(
            deviceId = "HOST-LOW",
            latestLocation = TEST_DEVICES[0].latestLocation?.copy(battery = 15)
        )
        fakeRepo.devicesResult = Result.success(listOf(TEST_DEVICES[0], lowBattDevice))
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFleetFilterChanged(FleetFilter.LOW_BATTERY)

        val lowBattDevices = viewModel.uiState.value.displayDevices
        assertEquals(1, lowBattDevices.size)
        assertEquals("HOST-LOW", lowBattDevices[0].deviceId)
    }

    @Test
    fun `buildMarkers does not fabricate movement from accuracy or emergency from low battery`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        val deviceWithAccuracy = TEST_DEVICES[0].copy(
            latestLocation = TEST_DEVICES[0].latestLocation?.copy(accuracy = 5.0, battery = 80)
        )
        val lowBattDevice = TEST_DEVICES[0].copy(
            deviceId = "HOST-LOWBATT",
            latestLocation = TEST_DEVICES[0].latestLocation?.copy(accuracy = 25.0, battery = 10)
        )
        val markers = viewModel.buildMarkers(listOf(deviceWithAccuracy, lowBattDevice))
        val marker1 = markers.find { it.deviceId == "HOST-0001" }!!
        val marker2 = markers.find { it.deviceId == "HOST-LOWBATT" }!!

        // Neither accuracy nor low battery should fabricate movement or emergency states
        assertFalse(marker1.isMoving)
        assertFalse(marker1.isEmergency)
        assertFalse(marker2.isMoving)
        assertFalse(marker2.isEmergency)
    }

    // ============================================================
    // Test data
    // ============================================================

    companion object {
        private val TEST_DEVICES = listOf(
            Device(
                deviceId = "HOST-0001",
                connectionId = "CONN-001",
                authenticated = true,
                registered = true,
                registrationState = "registered",
                heartbeatStatus = "online",
                connectedAt = "2026-07-09T12:00:00Z",
                lastHeartbeat = "2026-07-09T12:00:20Z",
                deviceName = "Pixel 9",
                appVersion = "1.0.0",
                model = "Google Pixel",
                latestLocation = DeviceLocation(
                    deviceId = "HOST-0001",
                    latitude = 28.6139,
                    longitude = 77.2090,
                    accuracy = 5.4,
                    battery = 81,
                    network = "5G",
                    recordedAt = "2026-07-09T12:00:10Z"
                )
            ),
            Device(
                deviceId = "HOST-0002",
                connectionId = "CONN-002",
                authenticated = true,
                registered = false,
                registrationState = "pending",
                heartbeatStatus = "offline",
                connectedAt = "2026-07-09T11:00:00Z",
                lastHeartbeat = "2026-07-09T11:05:00Z",
                deviceName = "OnePlus 12",
                appVersion = "1.0.0",
                model = "OnePlus",
                latestLocation = null
            ),
            Device(
                deviceId = "HOST-0003",
                connectionId = "CONN-003",
                authenticated = true,
                registered = true,
                registrationState = "registered",
                heartbeatStatus = "online",
                connectedAt = "2026-07-09T10:00:00Z",
                lastHeartbeat = "2026-07-09T10:30:00Z",
                deviceName = "Galaxy S24",
                appVersion = "1.0.0",
                model = "Samsung",
                latestLocation = DeviceLocation(
                    deviceId = "HOST-0003",
                    latitude = 19.0760,
                    longitude = 72.8777,
                    accuracy = 10.0,
                    battery = 45,
                    network = "WiFi",
                    recordedAt = "2026-07-09T10:28:00Z"
                )
            )
        )
    }
}
