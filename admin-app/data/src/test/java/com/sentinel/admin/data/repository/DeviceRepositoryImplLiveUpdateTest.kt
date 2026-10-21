package com.sentinel.admin.data.repository

import com.sentinel.admin.data.remote.api.DeviceApi
import com.sentinel.admin.data.remote.api.DeviceDto
import com.sentinel.admin.data.remote.api.DevicesResponse
import com.sentinel.admin.data.remote.protocol.DeviceUpdateDataJson
import com.sentinel.admin.data.remote.protocol.DeviceUpdateEventMapper
import com.sentinel.admin.data.remote.protocol.DeviceUpdateMessageJson
import com.sentinel.admin.domain.model.ConnectionEvent
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.domain.repository.ConnectionRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [DeviceRepositoryImpl] live WebSocket update handling.
 *
 * Seeds the device map by emitting "connected" events (not reflection),
 * then tests incremental patching, duplicate/stale suppression,
 * and offline marking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryImplLiveUpdateTest {

    private lateinit var scope: TestScope
    private lateinit var eventsFlow: MutableSharedFlow<ConnectionEvent>
    private lateinit var repo: DeviceRepositoryImpl
    private lateinit var moshi: Moshi
    private val messageAdapter by lazy { moshi.adapter(DeviceUpdateMessageJson::class.java) }

    /** Stub DeviceApi — REST methods are not tested here. */
    private val stubDeviceApi = object : DeviceApi {
        override suspend fun getDevices(authorization: String): DevicesResponse =
            DevicesResponse(emptyList())
        override suspend fun getDevice(authorization: String, deviceId: String): DeviceDto =
            throw NotImplementedError("not used in live update tests")
        override suspend fun wakeDevice(authorization: String, deviceId: String): com.sentinel.admin.data.remote.api.WakeResponseDto =
            com.sentinel.admin.data.remote.api.WakeResponseDto(success = true, deviceId = deviceId, messageId = "test-msg-id")
        override suspend fun getRouteHistory(
            authorization: String,
            deviceId: String,
            start: String?,
            end: String?
        ): com.sentinel.admin.data.remote.api.RouteHistoryResponse =
            com.sentinel.admin.data.remote.api.RouteHistoryResponse(deviceId = deviceId, points = emptyList())
        override suspend fun getBatteryHistory(
            authorization: String,
            deviceId: String
        ): com.sentinel.admin.data.remote.api.BatteryHistoryResponse =
            com.sentinel.admin.data.remote.api.BatteryHistoryResponse(deviceId = deviceId, history = emptyList())
    }

    @Before
    fun setUp() {
        scope = TestScope(UnconfinedTestDispatcher())
        eventsFlow = MutableSharedFlow(extraBufferCapacity = 64)
        moshi = Moshi.Builder().build()

        val fakeConnectionRepo = object : ConnectionRepository {
            override val events: SharedFlow<ConnectionEvent> = eventsFlow
            override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
            override suspend fun connect(url: String) {}
            override suspend fun disconnect() {}
            override fun sendText(message: String): Boolean = true
            override fun sendBinary(data: ByteArray): Boolean = true
        }

        val fakeAuthRepo = object : com.sentinel.admin.domain.repository.AuthRepository {
            override fun getToken(): String? = "test-token"
            override fun saveToken(token: String) {}
            override fun clearToken() {}
        }

        repo = DeviceRepositoryImpl(
            deviceApi = stubDeviceApi,
            authRepository = fakeAuthRepo,
            connectionRepository = fakeConnectionRepo,
            eventMapper = DeviceUpdateEventMapper(),
            moshi = moshi,
            scope = scope.backgroundScope
        )
    }

    /**
     * Emits a DEVICE_UPDATE message through the events flow.
     */
    private fun emitUpdate(data: DeviceUpdateDataJson, sequence: Long = 0) {
        val msg = DeviceUpdateMessageJson(
            type = "DEVICE_UPDATE",
            version = 1,
            timestamp = System.currentTimeMillis(),
            sequence = sequence,
            data = data
        )
        val json = messageAdapter.toJson(msg)
        eventsFlow.tryEmit(ConnectionEvent.DeviceUpdateReceived(json))
    }

    /**
     * Seeds a device into the map via a "connected" event.
     * Must call advanceUntilIdle() after to process.
     */
    private fun emitConnected(
        deviceId: String = "HOST-001",
        deviceName: String = "Test Device",
        sequence: Long = 0,
        model: String = "TestModel"
    ) {
        emitUpdate(
            DeviceUpdateDataJson(
                event = "connected",
                deviceId = deviceId,
                deviceName = deviceName,
                model = model,
                appVersion = "1.0"
            ),
            sequence = sequence
        )
    }

    /**
     * Seeds a device with a location by emitting connected + location events.
     * Must call advanceUntilIdle() after to process.
     */
    private fun emitConnectedWithLocation(
        deviceId: String = "HOST-001",
        deviceName: String = "Test Device",
        lat: Double = 37.0,
        lng: Double = -122.0,
        battery: Int = 80,
        network: String = "wifi",
        connectedSeq: Long = 1,
        locationSeq: Long = 2
    ) {
        emitConnected(deviceId, deviceName, connectedSeq)
        emitUpdate(
            DeviceUpdateDataJson(
                event = "location",
                deviceId = deviceId,
                latitude = lat,
                longitude = lng,
                accuracy = 5.0,
                battery = battery,
                network = network
            ),
            sequence = locationSeq
        )
    }

    // ============================================================
    // Connected events
    // ============================================================

    @Test
    fun `connected event creates device in map`() = scope.runTest {
        emitConnected(sequence = 1)
        advanceUntilIdle()

        val device = repo.devices.value["HOST-001"]
        assertNotNull(device)
        assertEquals("Test Device", device!!.deviceName)
        assertEquals("online", device.heartbeatStatus)
        assertEquals(1L, repo.eventStatistics.value.applied)
    }

    @Test
    fun `connected event patches existing offline device back online`() = scope.runTest {
        // Connect then disconnect
        emitConnected(sequence = 1)
        advanceUntilIdle()
        emitUpdate(
            DeviceUpdateDataJson(event = "disconnected", deviceId = "HOST-001"),
            sequence = 2
        )
        advanceUntilIdle()
        assertEquals("offline", repo.devices.value["HOST-001"]!!.heartbeatStatus)

        // Reconnect with updated name
        emitUpdate(
            DeviceUpdateDataJson(
                event = "connected",
                deviceId = "HOST-001",
                deviceName = "Updated Name"
            ),
            sequence = 3
        )
        advanceUntilIdle()

        val device = repo.devices.value["HOST-001"]
        assertNotNull(device)
        assertEquals("online", device!!.heartbeatStatus)
        assertEquals("Updated Name", device.deviceName)
    }

    // ============================================================
    // Heartbeat updates
    // ============================================================

    @Test
    fun `heartbeat update patches existing device`() = scope.runTest {
        emitConnected(sequence = 1)
        advanceUntilIdle()

        emitUpdate(
            DeviceUpdateDataJson(
                event = "heartbeat",
                deviceId = "HOST-001",
                timestamp = "2026-07-18T12:00:00Z"
            ),
            sequence = 2
        )
        advanceUntilIdle()

        val updated = repo.devices.value["HOST-001"]
        assertNotNull(updated)
        assertEquals("online", updated!!.heartbeatStatus)
        assertEquals("2026-07-18T12:00:00Z", updated.lastHeartbeat)
        assertEquals(2L, repo.eventStatistics.value.applied) // connected + heartbeat
    }

    @Test
    fun `heartbeat for unknown device is ignored`() = scope.runTest {
        emitUpdate(
            DeviceUpdateDataJson(
                event = "heartbeat",
                deviceId = "UNKNOWN",
                timestamp = "2026-07-18T12:00:00Z"
            ),
            sequence = 1
        )
        advanceUntilIdle()

        // Applied counter increments (event was valid), but no device in map
        assertTrue(repo.devices.value["UNKNOWN"] == null)
    }

    // ============================================================
    // Disconnect updates
    // ============================================================

    @Test
    fun `disconnect marks device offline not removed`() = scope.runTest {
        emitConnected(sequence = 1)
        advanceUntilIdle()

        emitUpdate(
            DeviceUpdateDataJson(event = "disconnected", deviceId = "HOST-001"),
            sequence = 2
        )
        advanceUntilIdle()

        val updated = repo.devices.value["HOST-001"]
        assertNotNull("Device should still be in map", updated)
        assertEquals("offline", updated!!.heartbeatStatus)
    }

    // ============================================================
    // Location updates
    // ============================================================

    @Test
    fun `location update patches coordinates`() = scope.runTest {
        emitConnectedWithLocation(connectedSeq = 1, locationSeq = 2)
        advanceUntilIdle()

        // Verify initial location
        val loc1 = repo.devices.value["HOST-001"]?.latestLocation
        assertNotNull(loc1)
        assertEquals(37.0, loc1!!.latitude, 0.001)

        // Update location
        emitUpdate(
            DeviceUpdateDataJson(
                event = "location",
                deviceId = "HOST-001",
                latitude = 38.0,
                longitude = -123.0,
                accuracy = 3.0,
                battery = 75,
                network = "5g"
            ),
            sequence = 3
        )
        advanceUntilIdle()

        val loc2 = repo.devices.value["HOST-001"]?.latestLocation
        assertNotNull(loc2)
        assertEquals(38.0, loc2!!.latitude, 0.001)
        assertEquals(-123.0, loc2.longitude, 0.001)
        assertEquals(75, loc2.battery)
        assertEquals("5g", loc2.network)
    }

    // ============================================================
    // Metadata updates
    // ============================================================

    @Test
    fun `metadata update patches device name`() = scope.runTest {
        emitConnected(sequence = 1)
        advanceUntilIdle()

        emitUpdate(
            DeviceUpdateDataJson(
                event = "metadata",
                deviceId = "HOST-001",
                deviceName = "Renamed Device"
            ),
            sequence = 2
        )
        advanceUntilIdle()

        assertEquals("Renamed Device", repo.devices.value["HOST-001"]?.deviceName)
    }

    // ============================================================
    // Duplicate suppression
    // ============================================================

    @Test
    fun `duplicate sequence is rejected`() = scope.runTest {
        emitConnected(sequence = 1)
        advanceUntilIdle()

        emitUpdate(
            DeviceUpdateDataJson(event = "heartbeat", deviceId = "HOST-001", timestamp = "t1"),
            sequence = 5
        )
        advanceUntilIdle()

        emitUpdate(
            DeviceUpdateDataJson(event = "heartbeat", deviceId = "HOST-001", timestamp = "t2"),
            sequence = 5
        )
        advanceUntilIdle()

        assertEquals(2L, repo.eventStatistics.value.applied) // connected + first heartbeat
        assertEquals(1L, repo.eventStatistics.value.duplicates)
    }

    // ============================================================
    // Stale rejection
    // ============================================================

    @Test
    fun `stale sequence is rejected`() = scope.runTest {
        emitConnected(sequence = 1)
        advanceUntilIdle()

        emitUpdate(
            DeviceUpdateDataJson(event = "heartbeat", deviceId = "HOST-001", timestamp = "t1"),
            sequence = 10
        )
        advanceUntilIdle()

        emitUpdate(
            DeviceUpdateDataJson(event = "heartbeat", deviceId = "HOST-001", timestamp = "t2"),
            sequence = 5
        )
        advanceUntilIdle()

        assertEquals(2L, repo.eventStatistics.value.applied) // connected + first heartbeat
        assertEquals(1L, repo.eventStatistics.value.stale)
    }

    // ============================================================
    // Unknown events
    // ============================================================

    @Test
    fun `unknown event type increments ignored counter`() = scope.runTest {
        emitConnected(sequence = 1)
        advanceUntilIdle()

        emitUpdate(
            DeviceUpdateDataJson(event = "future_unknown_event", deviceId = "HOST-001"),
            sequence = 2
        )
        advanceUntilIdle()

        assertEquals(1L, repo.eventStatistics.value.applied) // only connected
        assertEquals(1L, repo.eventStatistics.value.ignored) // unknown ignored
    }

    // ============================================================
    // Malformed payload
    // ============================================================

    @Test
    fun `malformed JSON increments ignored counter`() = scope.runTest {
        eventsFlow.tryEmit(ConnectionEvent.DeviceUpdateReceived("not valid json{{{"))
        advanceUntilIdle()

        assertEquals(0L, repo.eventStatistics.value.applied)
        assertEquals(1L, repo.eventStatistics.value.ignored)
    }

    // ============================================================
    // Sequence ordering across multiple devices
    // ============================================================

    @Test
    fun `different devices track sequences independently`() = scope.runTest {
        emitConnected("HOST-001", "Device 1", sequence = 1)
        emitConnected("HOST-002", "Device 2", sequence = 1)
        advanceUntilIdle()

        assertEquals(2L, repo.eventStatistics.value.applied)

        // HOST-001 at seq 10, HOST-002 at seq 5
        emitUpdate(
            DeviceUpdateDataJson(event = "heartbeat", deviceId = "HOST-001", timestamp = "t1"),
            sequence = 10
        )
        emitUpdate(
            DeviceUpdateDataJson(event = "heartbeat", deviceId = "HOST-002", timestamp = "t2"),
            sequence = 5
        )
        advanceUntilIdle()

        assertEquals(4L, repo.eventStatistics.value.applied) // 2 connected + 2 heartbeats

        // HOST-001 at seq 8 (stale) but HOST-002 at seq 8 (valid)
        emitUpdate(
            DeviceUpdateDataJson(event = "heartbeat", deviceId = "HOST-001", timestamp = "t3"),
            sequence = 8
        )
        emitUpdate(
            DeviceUpdateDataJson(event = "heartbeat", deviceId = "HOST-002", timestamp = "t4"),
            sequence = 8
        )
        advanceUntilIdle()

        assertEquals(5L, repo.eventStatistics.value.applied) // +1 HOST-002
        assertEquals(1L, repo.eventStatistics.value.stale)   // HOST-001's seq 8
    }

    @Test
    fun `dedicated telemetry report updates specific device and isolates from generic broadcast`() = scope.runTest {
        // Two devices with same deviceId but different models
        emitConnected("HOST-001", "Samsung S24", model = "SM-S928B", sequence = 1)
        emitConnected("HOST-001", "Vivo Phone", model = "I2401", sequence = 2)
        advanceUntilIdle()

        assertEquals(2, repo.devices.value.values.distinctBy { it.uniqueKey }.size)

        // Samsung sends dedicated TELEMETRY_REPORT with battery 45%
        val telemetryJson = """
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12345,
                "sequence": 3,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001",
                        "model": "SM-S928B",
                        "uniqueKey": "HOST-001_SM-S928B",
                        "battery": 45,
                        "network": "WiFi (Charging)",
                        "latitude": 28.3657,
                        "longitude": 77.5402,
                        "accuracy": 10.0
                    }
                }
            }
        """.trimIndent()
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived(telemetryJson))
        advanceUntilIdle()

        val samsung = repo.devices.value["HOST-001_SM-S928B"]!!
        assertEquals(45, samsung.latestLocation?.battery)
        assertEquals("WiFi (Charging)", samsung.latestLocation?.network)

        // Server sends generic LOCATION broadcast for HOST-001 with battery 50% (from Vivo)
        emitUpdate(
            DeviceUpdateDataJson(
                event = "location",
                deviceId = "HOST-001",
                latitude = 28.3658,
                longitude = 77.5403,
                accuracy = 8.0,
                battery = 50,
                network = "WiFi"
            ),
            sequence = 4
        )
        advanceUntilIdle()

        // Samsung must still have 45% (isolated!)
        val samsungAfter = repo.devices.value["HOST-001_SM-S928B"]!!
        assertEquals(45, samsungAfter.latestLocation?.battery)
        assertEquals("WiFi (Charging)", samsungAfter.latestLocation?.network)

        // Vivo must have 50%
        val vivoAfter = repo.devices.value["HOST-001_I2401"]!!
        assertEquals(50, vivoAfter.latestLocation?.battery)
        assertEquals("WiFi", vivoAfter.latestLocation?.network)
    }

    @Test
    fun `dedicated telemetry echo from server is suppressed and does not overwrite other device`() = scope.runTest {
        // Two devices sharing HOST-001
        emitConnected("HOST-001", "Samsung S24", model = "SM-S928B", sequence = 1)
        emitConnected("HOST-001", "Vivo Phone", model = "I2401", sequence = 2)
        advanceUntilIdle()

        // Samsung has 45% battery
        val samsungTelemetry = """
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12345,
                "sequence": 3,
                "data": {
                    "command": "GET_SYSTEM_INFO",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001",
                        "model": "SM-S928B",
                        "uniqueKey": "HOST-001_SM-S928B",
                        "batteryPercent": 45,
                        "isCharging": false
                    }
                }
            }
        """.trimIndent()
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived(samsungTelemetry))
        advanceUntilIdle()

        val samsungInitial = repo.devices.value["HOST-001_SM-S928B"]!!
        assertEquals(45, samsungInitial.latestLocation?.battery)

        // Vivo reports dedicated telemetry: 65% battery, charging, lat 28.3659, lng 77.5403
        val vivoTelemetry = """
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12346,
                "sequence": 4,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001",
                        "model": "I2401",
                        "uniqueKey": "HOST-001_I2401",
                        "battery": 65,
                        "network": "WiFi (Charging)",
                        "latitude": 28.3659,
                        "longitude": 77.5403,
                        "accuracy": 15.0
                    }
                }
            }
        """.trimIndent()
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived(vivoTelemetry))
        advanceUntilIdle()

        val vivoCurrent = repo.devices.value["HOST-001_I2401"]!!
        assertEquals(65, vivoCurrent.latestLocation?.battery)

        // Server broadcasts untagged generic DEVICE_UPDATE echo of Vivo's report (deviceId: HOST-001, battery: 65)
        emitUpdate(
            DeviceUpdateDataJson(
                event = "location",
                deviceId = "HOST-001",
                latitude = 28.3659,
                longitude = 77.5403,
                accuracy = 15.0,
                battery = 65,
                network = "WiFi (Charging)"
            ),
            sequence = 5
        )
        advanceUntilIdle()

        // CRITICAL CHECK: Samsung must NOT be overwritten with Vivo's 65% echo!
        val samsungAfterEcho = repo.devices.value["HOST-001_SM-S928B"]!!
        assertEquals(45, samsungAfterEcho.latestLocation?.battery)

        // Vivo must still be 65%
        val vivoAfterEcho = repo.devices.value["HOST-001_I2401"]!!
        assertEquals(65, vivoAfterEcho.latestLocation?.battery)
    }

    @Test
    fun `telemetry report prioritizes active online device over stale cached HOST-001 entry`() = scope.runTest {
        // Seed active online device with hardware ID / callsign
        emitConnected("HOST-001-VIVO-2D2C", "Vivo V40 Pro (HOST-VIVO-2D2C)", model = "I2401", sequence = 1)
        advanceUntilIdle()

        val onlineVivo = repo.devices.value["HOST-001-VIVO-2D2C"]
        assertNotNull(onlineVivo)
        assertEquals("online", onlineVivo?.heartbeatStatus)

        // Telemetry arrives with generic deviceId "HOST-001", model "I2401", callsign "HOST-VIVO-2D2C"
        val telemetry = """
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12345,
                "sequence": 2,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001",
                        "model": "I2401",
                        "callsign": "HOST-VIVO-2D2C",
                        "hardwareId": "VIVO-2D2C",
                        "battery": 92,
                        "network": "5G",
                        "latitude": 28.5355,
                        "longitude": 77.3910
                    }
                }
            }
        """.trimIndent()
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived(telemetry))
        advanceUntilIdle()

        // Active online device MUST be updated with 92% battery and location
        val updatedVivo = repo.devices.value["HOST-001-VIVO-2D2C"]
        assertNotNull(updatedVivo)
        assertEquals(92, updatedVivo?.latestLocation?.battery)
        assertEquals("5G", updatedVivo?.latestLocation?.network)
        assertEquals(28.5355, updatedVivo?.latestLocation?.latitude ?: 0.0, 0.001)

        // Stale "HOST-001" must NOT exist as a separate poisoned device
        val staleEntry = repo.devices.value["HOST-001"]
        assertTrue(staleEntry == null || staleEntry.uniqueKey == updatedVivo?.uniqueKey)
    }
    private fun emitTelemetry(id: String, model: String, battery: Int, extra: String = "") {
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived("""
            {"type":"COMMAND_RESULT","version":1,"timestamp":12345,"sequence":0,
             "data":{"command":"TELEMETRY_REPORT","success":true,
             "payload":{"deviceId":"$id","model":"$model","battery":$battery $extra}}}
        """.trimIndent()))
    }

    @Test
    fun `same model phones keep separate callsigns and telemetry`() = scope.runTest {
        emitConnected("HOST-001-VIVO-AAAA", model = "I2401", sequence = 1)
        emitConnected("HOST-001-VIVO-BBBB", model = "I2401", sequence = 1)
        advanceUntilIdle()
        val first = repo.devices.value["HOST-001-VIVO-AAAA"]!!
        val second = repo.devices.value["HOST-001-VIVO-BBBB"]!!
        assertTrue(first.callsign != second.callsign)
        emitTelemetry(first.deviceId, "I2401", 91)
        emitTelemetry(second.deviceId, "I2401", 42)
        advanceUntilIdle()
        assertEquals(91, repo.devices.value[first.deviceId]!!.latestLocation!!.battery)
        assertEquals(42, repo.devices.value[second.deviceId]!!.latestLocation!!.battery)
    }

    @Test
    fun `unknown permanent identity never updates another phone of same model`() = scope.runTest {
        emitConnected("HOST-001-VIVO-AAAA", model = "I2401", sequence = 1)
        advanceUntilIdle()
        emitTelemetry("HOST-001-VIVO-BBBB", "I2401", 42)
        advanceUntilIdle()
        assertEquals(null, repo.devices.value["HOST-001-VIVO-AAAA"]!!.latestLocation)
        assertEquals(42, repo.devices.value["HOST-001-VIVO-BBBB"]!!.latestLocation!!.battery)
    }

    @Test
    fun `ambiguous generic telemetry cannot choose a same model phone`() = scope.runTest {
        emitConnected("HOST-001-VIVO-AAAA", model = "I2401", sequence = 1)
        emitConnected("HOST-001-VIVO-BBBB", model = "I2401", sequence = 1)
        advanceUntilIdle()
        emitTelemetry("HOST-001", "I2401", 88)
        advanceUntilIdle()
        assertEquals(null, repo.devices.value["HOST-001-VIVO-AAAA"]!!.latestLocation)
        assertEquals(null, repo.devices.value["HOST-001-VIVO-BBBB"]!!.latestLocation)
    }

    @Test
    fun `legacy devices do not share sequence ordering or metadata`() = scope.runTest {
        emitConnected("HOST-001", "Samsung", sequence = 100, model = "SM-S928B")
        emitConnected("HOST-001", "Vivo", sequence = 1, model = "I2401")
        advanceUntilIdle()
        assertEquals("Samsung", repo.devices.value["HOST-001_SM-S928B"]!!.deviceName)
        assertEquals("Vivo", repo.devices.value["HOST-001_I2401"]!!.deviceName)
        assertEquals(0, repo.eventStatistics.value.stale.toInt())
    }

    @Test
    fun `dedicated telemetry accepts genuine 0,0 Gulf of Guinea fix and sets hasReportedFix true`() = scope.runTest {
        emitConnected("HOST-001-VIVO-2D2C", model = "I2401", sequence = 1)
        advanceUntilIdle()

        val telemetry = """
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12345,
                "sequence": 2,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001-VIVO-2D2C",
                        "model": "I2401",
                        "battery": 88,
                        "network": "5G",
                        "latitude": 0.0,
                        "longitude": 0.0,
                        "accuracy": 4.5,
                        "timestamp": "2026-10-12T04:15:00Z"
                    }
                }
            }
        """.trimIndent()
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived(telemetry))
        advanceUntilIdle()

        val loc = repo.devices.value["HOST-001-VIVO-2D2C"]?.latestLocation
        assertNotNull(loc)
        assertTrue("Genuine 0,0 fix must set hasReportedFix true", loc!!.hasReportedFix)
        assertEquals(0.0, loc.latitude, 0.00001)
        assertEquals(0.0, loc.longitude, 0.00001)
        assertEquals("2026-10-12T04:15:00Z", loc.recordedAt)
    }

    @Test
    fun `dedicated telemetry parses numeric epoch timestamp`() = scope.runTest {
        emitConnected("HOST-001-VIVO-2D2C", model = "I2401", sequence = 1)
        advanceUntilIdle()

        val telemetry = """
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12345,
                "sequence": 2,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001-VIVO-2D2C",
                        "model": "I2401",
                        "battery": 90,
                        "network": "LTE",
                        "latitude": 28.5,
                        "longitude": 77.2,
                        "accuracy": 5.0,
                        "timestamp": 1728419020000
                    }
                }
            }
        """.trimIndent()
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived(telemetry))
        advanceUntilIdle()

        val loc = repo.devices.value["HOST-001-VIVO-2D2C"]?.latestLocation
        assertNotNull(loc)
        assertTrue(loc!!.hasReportedFix)
        assertTrue("Numeric timestamp must be converted to ISO string", loc.recordedAt.contains("T") && loc.recordedAt.endsWith("Z"))
    }

    @Test
    fun `sample time is decoupled from receipt time using advancing receipt clock`() = scope.runTest {
        var currentInstant = java.time.Instant.parse("2026-10-12T10:00:00Z")
        val fakeClock = object : java.time.Clock() {
            override fun getZone(): java.time.ZoneId = java.time.ZoneId.of("UTC")
            override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
            override fun instant(): java.time.Instant = currentInstant
        }

        val clockRepo = DeviceRepositoryImpl(
            deviceApi = stubDeviceApi,
            authRepository = object : com.sentinel.admin.domain.repository.AuthRepository {
                override fun getToken(): String? = "test-token"
                override fun saveToken(token: String) {}
                override fun clearToken() {}
            },
            connectionRepository = object : ConnectionRepository {
                override val events: SharedFlow<ConnectionEvent> = eventsFlow
                override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
                override suspend fun connect(url: String) {}
                override suspend fun disconnect() {}
                override fun sendText(message: String): Boolean = true
                override fun sendBinary(data: ByteArray): Boolean = true
            },
            eventMapper = DeviceUpdateEventMapper(),
            moshi = moshi,
            scope = scope.backgroundScope,
            receiptClock = fakeClock
        )

        // Initial connection
        emitConnected("HOST-001-VIVO-2D2C", model = "I2401", sequence = 1)
        advanceUntilIdle()

        // First fix at T0 with sample time T - 5m
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived("""
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12345,
                "sequence": 2,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001-VIVO-2D2C",
                        "model": "I2401",
                        "battery": 80,
                        "latitude": 28.5,
                        "longitude": 77.2,
                        "accuracy": 5.0,
                        "timestamp": "2026-10-12T09:55:00Z"
                    }
                }
            }
        """.trimIndent()))
        advanceUntilIdle()

        val loc1 = clockRepo.devices.value["HOST-001-VIVO-2D2C"]?.latestLocation
        assertNotNull(loc1)
        assertEquals("2026-10-12T09:55:00Z", loc1?.recordedAt)
        assertEquals("2026-10-12T10:00:00Z", loc1?.receivedAt)

        // Advance clock by 30 seconds
        currentInstant = java.time.Instant.parse("2026-10-12T10:00:30Z")

        // Second fix with new sample time
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived("""
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12345,
                "sequence": 3,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001-VIVO-2D2C",
                        "model": "I2401",
                        "battery": 79,
                        "latitude": 28.501,
                        "longitude": 77.201,
                        "accuracy": 4.0,
                        "timestamp": "2026-10-12T10:00:25Z"
                    }
                }
            }
        """.trimIndent()))
        advanceUntilIdle()

        val loc2 = clockRepo.devices.value["HOST-001-VIVO-2D2C"]?.latestLocation
        assertNotNull(loc2)
        assertEquals("2026-10-12T10:00:25Z", loc2?.recordedAt)
        assertEquals("2026-10-12T10:00:30Z", loc2?.receivedAt)
    }

    @Test
    fun `two phones of same model with distinct hardware IDs remain separate and update locations independently`() = scope.runTest {
        // Device 1 connects
        emitConnected(
            deviceId = "HOST-001-VIVO-CFAE",
            deviceName = "Vivo Y200 (HOST-VIVO-CFAE)",
            model = "I2401",
            sequence = 1
        )
        // Device 2 connects (same model I2401, different hardware ID)
        emitConnected(
            deviceId = "HOST-001-VIVO-4109",
            deviceName = "Vivo Y200 (HOST-VIVO-4109)",
            model = "I2401",
            sequence = 2
        )
        advanceUntilIdle()

        val devices = repo.devices.value.values.distinctBy { it.uniqueKey }
        assertEquals(2, devices.size)

        val dev1 = repo.devices.value["HOST-001-VIVO-CFAE"]
        assertNotNull(dev1)
        assertEquals("HOST001-VIVO-CFAE", dev1?.callsign)
        assertEquals("HOST001-VIVO-CFAE", dev1?.resolvedCallsign)

        val dev2 = repo.devices.value["HOST-001-VIVO-4109"]
        assertNotNull(dev2)
        assertEquals("HOST002-VIVO-4109", dev2?.callsign)
        assertEquals("HOST002-VIVO-4109", dev2?.resolvedCallsign)

        // Device 1 reports location (lat: 28.50, lng: 77.20, bat: 85)
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived("""
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12345,
                "sequence": 3,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001-VIVO-CFAE",
                        "model": "I2401",
                        "callsign": "HOST-VIVO-CFAE",
                        "battery": 85,
                        "network": "5G",
                        "latitude": 28.5000,
                        "longitude": 77.2000,
                        "accuracy": 5.0,
                        "timestamp": "2026-10-21T01:00:00Z"
                    }
                }
            }
        """.trimIndent()))

        // Device 2 reports location (lat: 19.07, lng: 72.87, bat: 60)
        eventsFlow.tryEmit(ConnectionEvent.CommandResultReceived("""
            {
                "type": "COMMAND_RESULT",
                "version": 1,
                "timestamp": 12346,
                "sequence": 4,
                "data": {
                    "command": "TELEMETRY_REPORT",
                    "success": true,
                    "payload": {
                        "deviceId": "HOST-001-VIVO-4109",
                        "model": "I2401",
                        "callsign": "HOST-VIVO-4109",
                        "battery": 60,
                        "network": "WiFi",
                        "latitude": 19.0700,
                        "longitude": 72.8700,
                        "accuracy": 3.0,
                        "timestamp": "2026-10-21T01:00:05Z"
                    }
                }
            }
        """.trimIndent()))
        advanceUntilIdle()

        // Verify Device 1's location
        val updatedDev1 = repo.devices.value["HOST-001-VIVO-CFAE"]
        assertNotNull(updatedDev1?.latestLocation)
        assertEquals(28.5000, updatedDev1!!.latestLocation!!.latitude, 0.0001)
        assertEquals(77.2000, updatedDev1.latestLocation!!.longitude, 0.0001)
        assertEquals(85, updatedDev1.latestLocation!!.battery)
        assertEquals("5G", updatedDev1.latestLocation!!.network)

        // Verify Device 2's location
        val updatedDev2 = repo.devices.value["HOST-001-VIVO-4109"]
        assertNotNull(updatedDev2?.latestLocation)
        assertEquals(19.0700, updatedDev2!!.latestLocation!!.latitude, 0.0001)
        assertEquals(72.8700, updatedDev2.latestLocation!!.longitude, 0.0001)
        assertEquals(60, updatedDev2.latestLocation!!.battery)
        assertEquals("WiFi", updatedDev2.latestLocation!!.network)

        // Verify both still remain distinct in the device list
        val finalDevices = repo.devices.value.values.distinctBy { it.uniqueKey }
        assertEquals(2, finalDevices.size)
    }
}
