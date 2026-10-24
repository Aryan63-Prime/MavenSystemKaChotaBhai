package com.sentinel.admin.ui.detail

import com.sentinel.admin.data.audio.AudioOutput
import com.sentinel.admin.data.audio.NativeOpusDecoder
import com.sentinel.admin.domain.model.AudioStatistics
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.domain.model.DeviceUpdateEvent
import com.sentinel.admin.domain.model.EventStatistics
import com.sentinel.admin.domain.model.PlaybackState
import com.sentinel.admin.domain.repository.AudioRepository
import com.sentinel.admin.domain.repository.DeviceRepository
import com.sentinel.admin.service.AudioMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import androidx.lifecycle.SavedStateHandle
import com.sentinel.admin.data.remote.websocket.WebSocketDataSource
import com.sentinel.admin.domain.model.DeviceContact
import com.sentinel.admin.domain.model.DeviceContactBook
import com.sentinel.admin.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * Unit tests for [DeviceDetailViewModel].
 *
 * Covers:
 * - Initial load success
 * - Initial load error
 * - Refresh success
 * - Refresh error (preserves existing device)
 * - Retry after error
 * - Loading states
 * - UI state computed properties
 * - Not found error
 * - Unauthorized error
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModelTest {

    // ============================================================
    // Test doubles
    // ============================================================

    private class FakeDeviceRepository : DeviceRepository {
        var deviceResult: Result<Device> = Result.success(TEST_DEVICE)
        var devicesResult: Result<List<Device>> = Result.success(emptyList())
        var getDeviceCallCount = 0

        override val devices: StateFlow<Map<String, Device>> = MutableStateFlow(emptyMap())
        override val deviceUpdates: SharedFlow<DeviceUpdateEvent> = MutableSharedFlow()
        override val eventStatistics: StateFlow<EventStatistics> = MutableStateFlow(EventStatistics())
        override val emergencyAlert: StateFlow<com.sentinel.admin.domain.model.EmergencyAlert?> = MutableStateFlow(null)

        override fun dismissEmergencyAlert() {}
        override fun triggerTestEmergencyAlert(alert: com.sentinel.admin.domain.model.EmergencyAlert) {}
        override suspend fun wakeDevice(deviceId: String): Result<Boolean> = Result.success(true)

        override suspend fun getDevice(deviceId: String): Result<Device> {
            getDeviceCallCount++
            return deviceResult
        }

        override suspend fun fetchFreshDevice(deviceId: String): Result<Device> {
            getDeviceCallCount++
            return deviceResult
        }

        override suspend fun getDevices(): Result<List<Device>> {
            return devicesResult
        }
    }

    private class FakeAudioRepository : AudioRepository {
        var listenCalls = mutableListOf<String>()
        var stopCalls = mutableListOf<String>()

        override fun listen(deviceId: String, streamId: java.util.UUID?): Boolean {
            listenCalls.add(deviceId)
            return true
        }

        override fun stopListening(deviceId: String, streamId: java.util.UUID?): Boolean {
            stopCalls.add(deviceId)
            return true
        }
    }

    private class FakeAudioOutput : AudioOutput {
        override fun initialize() = true
        override fun write(pcm: ShortArray, offset: Int, size: Int) = size
        override fun pause() {}
        override fun resume() {}
        override fun release() {}
    }

    private class FakeContactRepository : ContactRepository {
        val contactsMap = mutableMapOf<String, MutableStateFlow<DeviceContactBook?>>()

        override fun getContactBook(deviceId: String): Flow<DeviceContactBook?> {
            val flow = contactsMap.computeIfAbsent(deviceId) {
                MutableStateFlow(
                    DeviceContactBook(
                        deviceId = deviceId,
                        total = 2,
                        contacts = listOf(
                            DeviceContact(name = "Alice Smith", phone = "+1 (555) 234-8901", type = "Mobile"),
                            DeviceContact(name = "Bob ICE", phone = "+1 (555) 911-3042", type = "Mobile", isEmergency = true)
                        ),
                        emergencyContact = DeviceContact(name = "Bob ICE", phone = "+1 (555) 911-3042", type = "Mobile", isEmergency = true),
                        lastSynced = "Sep 28, 2026 10:00"
                    )
                )
            }
            return flow
        }

        override suspend fun saveContactBook(deviceId: String, contactBook: DeviceContactBook): Result<Unit> {
            val flow = contactsMap.computeIfAbsent(deviceId) { MutableStateFlow(contactBook) }
            flow.value = contactBook
            return Result.success(Unit)
        }
    }

    // ============================================================
    // Setup
    // ============================================================

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeDeviceRepository
    private lateinit var fakeAudioRepo: FakeAudioRepository
    private lateinit var fakeContactRepo: FakeContactRepository
    private lateinit var audioMonitor: AudioMonitor
    private lateinit var webSocketDataSource: WebSocketDataSource
    private lateinit var fakePttRecorder: FakePttAudioRecorder

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeDeviceRepository()
        fakeAudioRepo = FakeAudioRepository()
        fakeContactRepo = FakeContactRepository()
        webSocketDataSource = WebSocketDataSource(OkHttpClient())
        fakePttRecorder = FakePttAudioRecorder()
        audioMonitor = AudioMonitor(
            decoder = NativeOpusDecoder(),
            audioOutput = FakeAudioOutput(),
            scope = TestScope(testDispatcher)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(deviceId: String = "HOST-0001"): DeviceDetailViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("deviceId" to deviceId))
        return DeviceDetailViewModel(
            savedStateHandle = savedStateHandle,
            deviceRepository = fakeRepo,
            audioRepository = fakeAudioRepo,
            contactRepository = fakeContactRepo,
            audioMonitor = audioMonitor,
            webSocketDataSource = webSocketDataSource,
            pttAudioRecorder = fakePttRecorder
        )
    }

    // ============================================================
    // Initial load
    // ============================================================

    @Test
    fun `initial state is loading`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `load success shows device`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.device)
        assertEquals("HOST-0001", state.device!!.deviceId)
        assertEquals("Pixel 9", state.device!!.deviceName)
        assertNull(state.errorMessage)
    }

    @Test
    fun `load success with location`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE_WITH_LOCATION)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val loc = viewModel.uiState.value.device!!.latestLocation
        assertNotNull(loc)
        assertEquals(28.6139, loc!!.latitude, 0.0001)
        assertEquals(81, loc.battery)
    }

    @Test
    fun `load error shows message`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.failure(RuntimeException("Server error"))
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.device)
        assertEquals("Server error", state.errorMessage)
    }

    @Test
    fun `load 404 shows not found`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.failure(NoSuchElementException("Device not found"))
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.errorMessage!!.contains("not found"))
    }

    @Test
    fun `load 401 shows unauthorized`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.failure(
            IllegalStateException("Unauthorized — invalid or expired token")
        )
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Unauthorized"))
    }

    // ============================================================
    // Refresh
    // ============================================================

    @Test
    fun `refresh sets isRefreshing`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isRefreshing)

        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `refresh updates device`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Pixel 9", viewModel.uiState.value.device!!.deviceName)

        fakeRepo.deviceResult = Result.success(
            TEST_DEVICE.copy(deviceName = "Pixel 9 Pro")
        )
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Pixel 9 Pro", viewModel.uiState.value.device!!.deviceName)
    }

    @Test
    fun `refresh error preserves existing device`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        fakeRepo.deviceResult = Result.failure(RuntimeException("Network error"))
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        // Device preserved, error shown
        assertNotNull(viewModel.uiState.value.device)
        assertEquals("Network error", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `refresh clears previous error on success`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // Trigger error
        fakeRepo.deviceResult = Result.failure(RuntimeException("fail"))
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)

        // Successful refresh clears error
        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `refresh transitions through Running to Success and scoped settling`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        val viewModel = createViewModel()
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
        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        val viewModel = createViewModel()
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
        fakeRepo.deviceResult = Result.failure(RuntimeException("fail"))
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)

        fakeRepo.deviceResult = Result.success(TEST_DEVICE)
        viewModel.retry()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
        assertNotNull(viewModel.uiState.value.device)
    }

    @Test
    fun `retry calls getDevice`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val initialCount = fakeRepo.getDeviceCallCount

        viewModel.retry()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(initialCount + 1, fakeRepo.getDeviceCallCount)
    }

    // ============================================================
    // UI State computed properties
    // ============================================================

    @Test
    fun `hasDevice true when device loaded`() {
        val state = DeviceDetailUiState(device = TEST_DEVICE)
        assertTrue(state.hasDevice)
    }

    @Test
    fun `hasDevice false when no device`() {
        val state = DeviceDetailUiState(device = null)
        assertFalse(state.hasDevice)
    }

    @Test
    fun `hasDevice false when error with no device`() {
        val state = DeviceDetailUiState(device = null, errorMessage = "error")
        assertFalse(state.hasDevice)
    }

    @Test
    fun `isOnline true when heartbeatStatus online`() {
        val state = DeviceDetailUiState(device = TEST_DEVICE)
        assertTrue(state.isOnline)
    }

    @Test
    fun `isOnline false when heartbeatStatus offline`() {
        val state = DeviceDetailUiState(
            device = TEST_DEVICE.copy(heartbeatStatus = "offline")
        )
        assertFalse(state.isOnline)
    }

    @Test
    fun `isOnline false when no device`() {
        val state = DeviceDetailUiState(device = null)
        assertFalse(state.isOnline)
    }

    // ============================================================
    // Audio
    // ============================================================

    @Test
    fun `initial playback state is Idle`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PlaybackState.Idle, viewModel.uiState.value.playbackState)
    }

    @Test
    fun `initial audio stats are zero`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AudioStatistics(), viewModel.uiState.value.audioStats)
    }

    @Test
    fun `onListenClick sends listen command`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onListenClick()
        assertEquals(1, fakeAudioRepo.listenCalls.size)
        assertEquals("HOST-0001", fakeAudioRepo.listenCalls[0])
    }

    @Test
    fun `onStopClick sends stop command`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onStopClick()
        assertEquals(1, fakeAudioRepo.stopCalls.size)
        assertEquals("HOST-0001", fakeAudioRepo.stopCalls[0])
    }

    // ============================================================
    // Contact details
    // ============================================================

    @Test
    fun `observes contact book on load`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.contactBook)
        assertEquals(2, viewModel.uiState.value.contactBook?.total)
        assertEquals("Alice Smith", viewModel.uiState.value.contactBook?.contacts?.first()?.name)
        assertEquals("Bob ICE", viewModel.uiState.value.contactBook?.emergencyContact?.name)
    }

    @Test
    fun `sendSyncContactsCommand sets syncing flag`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendSyncContactsCommand()
        assertTrue(viewModel.uiState.value.isSyncingContacts)
        assertEquals("Syncing contacts from host device...", viewModel.uiState.value.commandStatusMessage)
    }

    @Test
    fun `openAddressBookDialog sets flag to true`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAddressBookDialog)
        viewModel.openAddressBookDialog()
        assertTrue(viewModel.uiState.value.showAddressBookDialog)
    }

    @Test
    fun `dismissAddressBookDialog sets flag to false`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.openAddressBookDialog()
        assertTrue(viewModel.uiState.value.showAddressBookDialog)

        viewModel.dismissAddressBookDialog()
        assertFalse(viewModel.uiState.value.showAddressBookDialog)
    }

    @Test
    fun `dismissDialogs resets all dialog flags including address book`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.openAddressBookDialog()
        assertTrue(viewModel.uiState.value.showAddressBookDialog)

        viewModel.dismissDialogs()
        assertFalse(viewModel.uiState.value.showAddressBookDialog)
    }

    @Test
    fun `command result with hardware uniqueKey is accepted when screen uses legacy format`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE.copy(deviceId = "HOST-001", model = "I2401"))
        val viewModel = createViewModel(deviceId = "HOST-001_I2401")
        testDispatcher.scheduler.advanceUntilIdle()

        val json = """
            {
                "type": "COMMAND_RESULT",
                "data": {
                    "command": "LOCK_DEVICE",
                    "success": false,
                    "error": "HAL hardware failure",
                    "model": "I2401",
                    "uniqueKey": "HOST-001-VIVO-2D2C_I2401",
                    "deviceId": "HOST-001-VIVO-2D2C"
                }
            }
        """.trimIndent()
        webSocketDataSource.emitTestTextMessage(json)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("HAL hardware failure", viewModel.uiState.value.mdmActionMessage)
        assertEquals("Command failed: HAL hardware failure", viewModel.uiState.value.commandStatusMessage)
    }

    @Test
    fun `command result from different model is filtered out`() = runTest(testDispatcher) {
        fakeRepo.deviceResult = Result.success(TEST_DEVICE.copy(deviceId = "HOST-001", model = "I2401"))
        val viewModel = createViewModel(deviceId = "HOST-001_I2401")
        testDispatcher.scheduler.advanceUntilIdle()

        val json = """
            {
                "type": "COMMAND_RESULT",
                "data": {
                    "command": "LOCK_DEVICE",
                    "success": false,
                    "error": "Samsung error",
                    "model": "SM-S928B",
                    "uniqueKey": "HOST-001_SM-S928B",
                    "deviceId": "HOST-001"
                }
            }
        """.trimIndent()
        webSocketDataSource.emitTestTextMessage(json)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.commandStatusMessage)
    }

    @Test
    fun `computeKalmanKinematicPrediction calculates projected coordinates when moving`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val loc = DeviceLocation(
            deviceId = "HOST-0001",
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 5.0,
            battery = 80,
            network = "5G",
            recordedAt = "2026-07-09T12:00:00Z"
        )
        val prediction = viewModel.computeKalmanKinematicPrediction(
            currentLoc = loc,
            routePoints = emptyList(),
            azimuthDeg = 90,
            motionActivity = "IN_VEHICLE (60 km/h)"
        )

        assertNotNull(prediction.coords)
        assertTrue(prediction.summary?.contains("+15m Project") == true)
        val coords = viewModel.computeInterceptCoordinates(
            currentLoc = loc,
            routePoints = emptyList(),
            azimuthDeg = 90,
            motionActivity = "IN_VEHICLE (60 km/h)"
        )
        assertNotNull(coords)
        assertEquals(prediction.coords, coords)
    }

    @Test
    fun `computeKalmanKinematicPrediction returns null coordinates when stationary`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val loc = DeviceLocation(
            deviceId = "HOST-0001",
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 5.0,
            battery = 80,
            network = "5G",
            recordedAt = "2026-07-09T12:00:00Z"
        )
        val prediction = viewModel.computeKalmanKinematicPrediction(
            currentLoc = loc,
            routePoints = emptyList(),
            azimuthDeg = 90,
            motionActivity = "STATIONARY (0 km/h)"
        )

        assertNull(prediction.coords)
        assertTrue(prediction.summary?.contains("Holding Position") == true)
        val coords = viewModel.computeInterceptCoordinates(
            currentLoc = loc,
            routePoints = emptyList(),
            azimuthDeg = 90,
            motionActivity = "STATIONARY (0 km/h)"
        )
        assertNull(coords)
    }

    // ============================================================
    // Test data
    // ============================================================

    companion object {
        private val TEST_DEVICE = Device(
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
            latestLocation = null
        )

        private val TEST_DEVICE_WITH_LOCATION = TEST_DEVICE.copy(
            latestLocation = DeviceLocation(
                deviceId = "HOST-0001",
                latitude = 28.6139,
                longitude = 77.2090,
                accuracy = 5.4,
                battery = 81,
                network = "5G",
                recordedAt = "2026-07-09T12:00:10Z"
            )
        )
    }
}

class FakePttAudioRecorder : com.sentinel.admin.data.audio.PttAudioRecorder {
    override var isRecording: Boolean = false
    override fun start(onChunkAvailable: (pcmBase64: String, normalizedLevel: Float) -> Unit): Boolean {
        isRecording = true
        return true
    }
    override fun stop() {
        isRecording = false
    }
}
