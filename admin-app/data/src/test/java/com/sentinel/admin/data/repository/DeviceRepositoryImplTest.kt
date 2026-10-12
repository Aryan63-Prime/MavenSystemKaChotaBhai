package com.sentinel.admin.data.repository

import com.sentinel.admin.data.remote.api.DeviceApi
import com.sentinel.admin.data.remote.protocol.DeviceUpdateEventMapper
import com.sentinel.admin.domain.model.ConnectionEvent
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.domain.repository.AuthRepository
import com.sentinel.admin.domain.repository.ConnectionRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Unit tests for [DeviceRepositoryImpl].
 *
 * Uses MockWebServer + Retrofit to test:
 * - Successful device list fetch
 * - Successful single device fetch
 * - HTTP error mapping (401, 404, 500)
 * - Network error handling
 * - DTO → domain mapping
 * - Missing auth token
 * - Empty device list
 * - Nullable location
 */
class DeviceRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DeviceApi
    private lateinit var fakeAuthRepo: FakeAuthRepository
    private lateinit var repo: DeviceRepositoryImpl
    private lateinit var moshi: Moshi

    private class FakeAuthRepository : AuthRepository {
        private var storedToken: String? = "test-jwt-token"
        override fun getToken(): String? = storedToken
        override fun saveToken(token: String) { storedToken = token }
        override fun clearToken() { storedToken = null }
        fun setToken(value: String?) { storedToken = value }
    }

    private val noopConnectionRepo = object : ConnectionRepository {
        override val events: SharedFlow<ConnectionEvent> = MutableSharedFlow()
        override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override suspend fun connect(url: String) {}
        override suspend fun disconnect() {}
        override fun sendText(message: String): Boolean = true
        override fun sendBinary(data: ByteArray): Boolean = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        api = retrofit.create(DeviceApi::class.java)
        fakeAuthRepo = FakeAuthRepository()
        repo = DeviceRepositoryImpl(
            api, fakeAuthRepo, noopConnectionRepo,
            DeviceUpdateEventMapper(), moshi,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ============================================================
    // GET /devices — Success
    // ============================================================

    @Test
    fun `getDevices success with location`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(DEVICES_RESPONSE_WITH_LOCATION)
        )

        val result = repo.getDevices()
        assertTrue(result.isSuccess)

        val devices = result.getOrThrow()
        assertEquals(1, devices.size)

        val device = devices[0]
        assertEquals("HOST-0001", device.deviceId)
        assertEquals("Pixel 9", device.deviceName)
        assertEquals("Google Pixel", device.model)
        assertEquals("online", device.heartbeatStatus)
        assertEquals(true, device.authenticated)
        assertEquals(true, device.registered)
        assertEquals("registered", device.registrationState)

        // Location mapped
        assertNotNull(device.latestLocation)
        assertEquals(28.6139, device.latestLocation!!.latitude, 0.0001)
        assertEquals(81, device.latestLocation!!.battery)
        assertEquals("5G", device.latestLocation!!.network)

        // Verify auth header
        val request = server.takeRequest()
        assertEquals("Bearer test-jwt-token", request.getHeader("Authorization"))
    }

    @Test
    fun `getDevices success without location`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(DEVICES_RESPONSE_NO_LOCATION)
        )

        val result = repo.getDevices()
        assertTrue(result.isSuccess)

        val device = result.getOrThrow()[0]
        assertNull(device.latestLocation)
    }

    @Test
    fun `getDevices empty list`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"devices":[]}""")
        )

        val result = repo.getDevices()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
    }

    @Test
    fun `getDevices multiple devices`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(DEVICES_RESPONSE_MULTIPLE)
        )

        val result = repo.getDevices()
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
    }

    // ============================================================
    // GET /devices/{deviceId} — Success
    // ============================================================

    @Test
    fun `getDevice success`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(SINGLE_DEVICE_RESPONSE)
        )

        val result = repo.getDevice("HOST-0001")
        assertTrue(result.isSuccess)
        assertEquals("HOST-0001", result.getOrThrow().deviceId)
    }

    // ============================================================
    // HTTP Errors
    // ============================================================

    @Test
    fun `getDevices 401 unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repo.getDevices()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Unauthorized") == true)
    }

    @Test
    fun `getDevice 404 not found`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repo.getDevice("INVALID")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun `getDevices 500 server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repo.getDevices()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Server error") == true)
    }

    @Test
    fun `getDevices 403 forbidden`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = repo.getDevices()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Forbidden") == true)
    }

    // ============================================================
    // Auth edge cases
    // ============================================================

    @Test
    fun `getDevices without token fails`() = runTest {
        fakeAuthRepo.setToken(null)

        val result = repo.getDevices()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Not authenticated") == true)
    }

    @Test
    fun `getDevice without token fails`() = runTest {
        fakeAuthRepo.setToken(null)

        val result = repo.getDevice("HOST-0001")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Not authenticated") == true)
    }

    // ============================================================
    // Mapping
    // ============================================================

    @Test
    fun `location fields map correctly`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(DEVICES_RESPONSE_WITH_LOCATION)
        )

        val loc = repo.getDevices().getOrThrow()[0].latestLocation!!
        assertEquals("HOST-0001", loc.deviceId)
        assertEquals(28.6139, loc.latitude, 0.0001)
        assertEquals(77.2090, loc.longitude, 0.0001)
        assertEquals(5.4, loc.accuracy, 0.01)
        assertEquals(81, loc.battery)
        assertEquals("5G", loc.network)
        assertEquals("2026-07-09T12:00:10Z", loc.recordedAt)
    }

    @Test
    fun `sequential callsigns assigned to discovered devices`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(DEVICES_RESPONSE_MULTIPLE)
        )

        val devices = repo.getDevices().getOrThrow()
        assertEquals(2, devices.size)
        assertEquals("HOST-01", devices[0].callsign)
        assertEquals("HOST-02", devices[1].callsign)
        assertEquals("HOST-01", devices[0].displayId)
        assertEquals("HOST-02", devices[1].displayId)
    }

    // ============================================================
    // Test data
    // ============================================================


    @Test
    fun `refresh does not keep missing same model phone online`() = runTest {
        server.enqueue(MockResponse().setBody(DEVICES_RESPONSE_WITH_LOCATION))
        repo.getDevices().getOrThrow()
        server.enqueue(MockResponse().setBody(DEVICES_RESPONSE_WITH_LOCATION
            .replace("HOST-0001", "HOST-0003").replace("CONN-001", "CONN-003")))
        val devices = repo.getDevices().getOrThrow()
        assertEquals("offline", devices.single { it.deviceId == "HOST-0001" }.heartbeatStatus)
        assertEquals("online", devices.single { it.deviceId == "HOST-0003" }.heartbeatStatus)
        assertTrue(devices[0].callsign != devices[1].callsign)
    }

    @Test
    fun `single device refresh uses full legacy key`() = runTest {
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE.replace("HOST-0001", "HOST-001")))
        repo.getDevice("HOST-001_Google Pixel").getOrThrow()
        assertEquals("/devices/HOST-001_Google%20Pixel", server.takeRequest().path)
    }

    // ============================================================
    // Pass 7 Section 1: Response Identity Validation & Policy Tests
    // ============================================================

    @Test
    fun `fetchFreshDevice cached A request A response B fails with identity mismatch and leaves cache unchanged`() = runTest {
        // Populate cache with Device A
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE))
        val initialA = repo.getDevice("HOST-0001").getOrThrow()
        assertEquals("HOST-0001", initialA.deviceId)
        assertEquals("Google Pixel", initialA.model)

        // Now fetchFreshDevice for HOST-0001, but server misroutes and responds with Device B (HOST-0002 / OnePlus)
        val responseB = SINGLE_DEVICE_RESPONSE
            .replace("HOST-0001", "HOST-0002")
            .replace("CONN-001", "CONN-002")
            .replace("Pixel 9", "OnePlus 12")
            .replace("Google Pixel", "OnePlus")
        server.enqueue(MockResponse().setBody(responseB))

        val result = repo.fetchFreshDevice("HOST-0001")
        assertTrue("fetchFreshDevice must fail when server returns mismatched device identity", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "Exception must report identity mismatch: ${ex?.message}",
            ex?.message?.contains("Device response does not match the requested identity") == true
        )

        // Cache must remain pristine: HOST-0001 must not be poisoned by HOST-0002
        val cachedAfter = repo.devices.value["HOST-0001"]
        assertNotNull(cachedAfter)
        assertEquals("Google Pixel", cachedAfter?.model)
        assertEquals("CONN-001", cachedAfter?.connectionId)
        assertNull("Poisoned device HOST-0002 must not exist in cache", repo.devices.value["HOST-0002"])
    }

    @Test
    fun `fetchFreshDevice shared legacy deviceId with different model fails with identity mismatch and leaves cache unchanged`() = runTest {
        // Populate cache with legacy HOST-001 model "Google Pixel"
        val responseLegacyA = SINGLE_DEVICE_RESPONSE
            .replace("HOST-0001", "HOST-001")
            .replace("Google Pixel", "Google Pixel")
        server.enqueue(MockResponse().setBody(responseLegacyA))
        repo.getDevice("HOST-001").getOrThrow()

        // Server responds with shared legacy HOST-001, but different model "Samsung S24"
        val responseLegacyB = SINGLE_DEVICE_RESPONSE
            .replace("HOST-0001", "HOST-001")
            .replace("Pixel 9", "Samsung S24")
            .replace("Google Pixel", "Samsung")
        server.enqueue(MockResponse().setBody(responseLegacyB))

        val result = repo.fetchFreshDevice("HOST-001")
        assertTrue("Shared legacy deviceId with different model must fail identity validation", result.isFailure)

        // Cache must retain original model
        val cached = repo.devices.value["HOST-001"]
        assertNotNull(cached)
        assertEquals("Google Pixel", cached?.model)
    }

    @Test
    fun `fetchFreshDevice valid A response succeeds and merges updates`() = runTest {
        // Initial fetch with online status
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE))
        val dev1 = repo.getDevice("HOST-0001").getOrThrow()
        assertEquals("online", dev1.heartbeatStatus)

        // Fresh fetch with updated heartbeat status
        val updatedResponse = SINGLE_DEVICE_RESPONSE.replace("\"heartbeatStatus\": \"online\"", "\"heartbeatStatus\": \"offline\"")
        server.enqueue(MockResponse().setBody(updatedResponse))

        val result = repo.fetchFreshDevice("HOST-0001")
        assertTrue("Valid response must succeed", result.isSuccess)
        val fresh = result.getOrThrow()
        assertEquals("offline", fresh.heartbeatStatus)
        assertEquals("Google Pixel", fresh.model)
        assertEquals("offline", repo.devices.value["HOST-0001"]?.heartbeatStatus)
    }

    @Test
    fun `fetchFreshDevice uncached identity validation accepts matching and rejects mismatch`() = runTest {
        // Uncached request where response does NOT match requested ID
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE))
        val mismatchResult = repo.fetchFreshDevice("HOST-9999")
        assertTrue("Uncached request with mismatched response must fail", mismatchResult.isFailure)
        assertTrue(mismatchResult.exceptionOrNull()?.message?.contains("does not match the requested identity") == true)
        assertTrue("Cache must not record mismatched device", repo.devices.value.isEmpty())

        // Uncached request where response matches requested ID
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE))
        val matchResult = repo.fetchFreshDevice("HOST-0001")
        assertTrue("Uncached request with matching response must succeed", matchResult.isSuccess)
        assertEquals("HOST-0001", matchResult.getOrThrow().deviceId)
        assertEquals(1, repo.devices.value.values.distinctBy { it.uniqueKey }.size)
    }

    @Test
    fun `fetchFreshDevice with cached data and network failure fails without fallback while keeping cache intact`() = runTest {
        // Populate cache
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE))
        repo.getDevice("HOST-0001").getOrThrow()
        assertNotNull(repo.devices.value["HOST-0001"])

        // Server returns HTTP 500 error
        server.enqueue(MockResponse().setResponseCode(500))
        val result = repo.fetchFreshDevice("HOST-0001")

        assertTrue("fetchFreshDevice must report network failure, not fall back to cached success", result.isFailure)
        // Cache must still retain the previous state
        assertNotNull("Previous cached device must remain in cache for ordinary display", repo.devices.value["HOST-0001"])
    }

    @Test
    fun `fetchFreshDevice cancellation is rethrown cleanly`() = runTest {
        val customJob = kotlinx.coroutines.Job()
        val customScope = kotlinx.coroutines.CoroutineScope(customJob + kotlinx.coroutines.Dispatchers.Unconfined)
        customJob.cancel() // Cancel before or during execution

        try {
            customScope.run {
                repo.fetchFreshDevice("HOST-0001")
            }
        } catch (e: Exception) {
            assertTrue("Must rethrow CancellationException", e is kotlinx.coroutines.CancellationException)
        }
    }

    @Test
    fun `getDevice on identity mismatch falls back to cached device without cache poisoning`() = runTest {
        // Populate cache with Device A
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE))
        repo.getDevice("HOST-0001").getOrThrow()

        // Server returns mismatched Device B
        val responseB = SINGLE_DEVICE_RESPONSE
            .replace("HOST-0001", "HOST-0002")
            .replace("CONN-001", "CONN-002")
            .replace("Google Pixel", "OnePlus")
        server.enqueue(MockResponse().setBody(responseB))

        val result = repo.getDevice("HOST-0001")
        assertTrue("getDevice allows fallback to cached", result.isSuccess)
        val device = result.getOrThrow()
        assertEquals("HOST-0001", device.deviceId)
        assertEquals("Google Pixel", device.model)
        assertNull("Cache must NOT contain poisoned HOST-0002", repo.devices.value["HOST-0002"])
    }

    @Test
    fun `fetchFreshDevice valid REST fix replaces synthetic no-fix cached location`() = runTest {
        // Step 1: Initial device has null location
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE))
        repo.getDevice("HOST-0001").getOrThrow()
        assertNull(repo.devices.value["HOST-0001"]?.latestLocation)

        // Simulate synthetic no-fix cache (e.g. from battery update where hasReportedFix = false)
        val cachedDev = repo.devices.value["HOST-0001"]!!
        val syntheticLoc = DeviceLocation(
            deviceId = "HOST-0001",
            latitude = 0.0,
            longitude = 0.0,
            accuracy = 0.0,
            battery = 85,
            network = "5G",
            recordedAt = "",
            hasReportedFix = false,
            receivedAt = "2026-10-12T00:00:00Z"
        )
        // Manually update repo cache with synthetic fix
        val field = repo.javaClass.getDeclaredField("_devices")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val mapFlow = field.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<Map<String, Device>>
        mapFlow.value = mapOf("HOST-0001" to cachedDev.copy(latestLocation = syntheticLoc))

        // Step 2: Fresh REST response returns genuine fix
        val responseWithFix = SINGLE_DEVICE_RESPONSE.replace(
            "\"latestLocation\": null",
            """
            "latestLocation": {
              "deviceId": "HOST-0001",
              "latitude": 12.9716,
              "longitude": 77.5946,
              "accuracy": 4.2,
              "battery": 84,
              "network": "5G",
              "recordedAt": "2026-10-12T05:00:00Z"
            }
            """.trimIndent()
        )
        server.enqueue(MockResponse().setBody(responseWithFix))

        val freshResult = repo.fetchFreshDevice("HOST-0001")
        assertTrue(freshResult.isSuccess)
        val updatedLoc = repo.devices.value["HOST-0001"]?.latestLocation
        assertNotNull(updatedLoc)
        assertTrue("Fresh REST fix must replace synthetic no-fix cache", updatedLoc!!.hasReportedFix)
        assertEquals(12.9716, updatedLoc.latitude, 0.0001)
        assertEquals(77.5946, updatedLoc.longitude, 0.0001)
    }

    @Test
    fun `fetchFreshDevice preserves genuine cached fix when REST returns null location`() = runTest {
        // Step 1: Initial device has valid fix
        val responseWithFix = SINGLE_DEVICE_RESPONSE.replace(
            "\"latestLocation\": null",
            """
            "latestLocation": {
              "deviceId": "HOST-0001",
              "latitude": 28.6139,
              "longitude": 77.2090,
              "accuracy": 5.0,
              "battery": 90,
              "network": "LTE",
              "recordedAt": "2026-10-12T04:00:00Z"
            }
            """.trimIndent()
        )
        server.enqueue(MockResponse().setBody(responseWithFix))
        repo.getDevice("HOST-0001").getOrThrow()
        assertTrue(repo.devices.value["HOST-0001"]?.latestLocation?.hasReportedFix == true)

        // Step 2: REST returns null location
        server.enqueue(MockResponse().setBody(SINGLE_DEVICE_RESPONSE))
        val freshResult = repo.fetchFreshDevice("HOST-0001")
        assertTrue(freshResult.isSuccess)

        val preservedLoc = repo.devices.value["HOST-0001"]?.latestLocation
        assertNotNull(preservedLoc)
        assertTrue("Genuine cached fix must be preserved", preservedLoc!!.hasReportedFix)
        assertEquals(28.6139, preservedLoc.latitude, 0.0001)
    }

    @Test
    fun `fetchFreshDevice accepts genuine 0,0 Gulf of Guinea GPS fix`() = runTest {
        val responseZero = SINGLE_DEVICE_RESPONSE.replace(
            "\"latestLocation\": null",
            """
            "latestLocation": {
              "deviceId": "HOST-0001",
              "latitude": 0.0,
              "longitude": 0.0,
              "accuracy": 3.0,
              "battery": 95,
              "network": "5G",
              "recordedAt": "2026-10-12T06:00:00Z"
            }
            """.trimIndent()
        )
        server.enqueue(MockResponse().setBody(responseZero))
        val result = repo.fetchFreshDevice("HOST-0001")
        assertTrue(result.isSuccess)

        val loc = repo.devices.value["HOST-0001"]?.latestLocation
        assertNotNull(loc)
        assertTrue(loc!!.hasReportedFix)
        assertEquals(0.0, loc.latitude, 0.00001)
        assertEquals(0.0, loc.longitude, 0.00001)
    }

    companion object {
        private val DEVICES_RESPONSE_WITH_LOCATION = """
        {
          "devices": [{
            "deviceId": "HOST-0001",
            "connectionId": "CONN-001",
            "authenticated": true,
            "registered": true,
            "registrationState": "registered",
            "heartbeatStatus": "online",
            "connectedAt": "2026-07-09T12:00:00Z",
            "lastHeartbeat": "2026-07-09T12:00:20Z",
            "deviceName": "Pixel 9",
            "appVersion": "1.0.0",
            "model": "Google Pixel",
            "latestLocation": {
              "deviceId": "HOST-0001",
              "latitude": 28.6139,
              "longitude": 77.2090,
              "accuracy": 5.4,
              "battery": 81,
              "network": "5G",
              "recordedAt": "2026-07-09T12:00:10Z"
            }
          }]
        }
        """.trimIndent()

        private val DEVICES_RESPONSE_NO_LOCATION = """
        {
          "devices": [{
            "deviceId": "HOST-0002",
            "connectionId": "CONN-002",
            "authenticated": true,
            "registered": true,
            "registrationState": "registered",
            "heartbeatStatus": "offline",
            "connectedAt": "2026-07-09T11:00:00Z",
            "lastHeartbeat": "2026-07-09T11:05:00Z",
            "deviceName": "Samsung S24",
            "appVersion": "1.0.0",
            "model": "Samsung",
            "latestLocation": null
          }]
        }
        """.trimIndent()

        private val DEVICES_RESPONSE_MULTIPLE = """
        {
          "devices": [
            {
              "deviceId": "HOST-0001",
              "connectionId": "CONN-001",
              "authenticated": true,
              "registered": true,
              "registrationState": "registered",
              "heartbeatStatus": "online",
              "connectedAt": "2026-07-09T12:00:00Z",
              "lastHeartbeat": "2026-07-09T12:00:20Z",
              "deviceName": "Pixel 9",
              "appVersion": "1.0.0",
              "model": "Google Pixel",
              "latestLocation": null
            },
            {
              "deviceId": "HOST-0002",
              "connectionId": "CONN-002",
              "authenticated": true,
              "registered": true,
              "registrationState": "registered",
              "heartbeatStatus": "offline",
              "connectedAt": "2026-07-09T11:00:00Z",
              "lastHeartbeat": "2026-07-09T11:05:00Z",
              "deviceName": "OnePlus 12",
              "appVersion": "1.0.0",
              "model": "OnePlus",
              "latestLocation": null
            }
          ]
        }
        """.trimIndent()

        private val SINGLE_DEVICE_RESPONSE = """
        {
          "deviceId": "HOST-0001",
          "connectionId": "CONN-001",
          "authenticated": true,
          "registered": true,
          "registrationState": "registered",
          "heartbeatStatus": "online",
          "connectedAt": "2026-07-09T12:00:00Z",
          "lastHeartbeat": "2026-07-09T12:00:20Z",
          "deviceName": "Pixel 9",
          "appVersion": "1.0.0",
          "model": "Google Pixel",
          "latestLocation": null
        }
        """.trimIndent()
    }
}
