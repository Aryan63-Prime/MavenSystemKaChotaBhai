package com.sentinel.host.service

import com.sentinel.host.data.remote.ReconnectPolicy
import com.sentinel.host.data.remote.SequenceGenerator
import com.sentinel.host.data.remote.protocol.MessageSerializer
import com.sentinel.host.domain.model.ConnectionEvent
import com.sentinel.host.domain.model.ConnectionState
import com.sentinel.host.domain.model.DeviceInfo
import com.sentinel.host.domain.model.ReconnectConfig
import com.sentinel.host.domain.model.FileItem
import com.sentinel.host.domain.network.NetworkObserver
import com.sentinel.host.domain.repository.AuthRepository
import com.sentinel.host.domain.repository.ConnectionRepository
import com.sentinel.host.domain.repository.DeviceRepository
import com.sentinel.host.domain.repository.FileRepository
import com.sentinel.host.domain.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionSupervisorTest {

    private lateinit var testScope: TestScope
    private lateinit var supervisor: ConnectionSupervisor
    private lateinit var fakeEvents: MutableSharedFlow<ConnectionEvent>
    private lateinit var fakeRepo: FakeConnectionRepository
    private lateinit var fakeSession: FakeSessionManager
    private lateinit var fakeAuth: FakeAuthRepository
    private lateinit var fakeDevice: FakeDeviceRepository
    private lateinit var fakeNetwork: FakeNetworkObserver
    private lateinit var heartbeatScheduler: HeartbeatScheduler
    private lateinit var fakeLocationProvider: FakeLocationProvider
    private lateinit var locationStreamer: LocationStreamer

    @Before
    fun setUp() {
        testScope = TestScope()
        fakeEvents = MutableSharedFlow(extraBufferCapacity = 64)
        fakeRepo = FakeConnectionRepository(fakeEvents)
        fakeSession = FakeSessionManager()
        fakeAuth = FakeAuthRepository()
        fakeDevice = FakeDeviceRepository()
        fakeNetwork = FakeNetworkObserver()

        val policy = ReconnectPolicy(
            config = ReconnectConfig(
                initialDelayMs = 10,
                maxDelayMs = 100,
                maxAttempts = 3,
                multiplier = 2.0,
                jitterFactor = 0.0
            )
        )

        heartbeatScheduler = HeartbeatScheduler(
            connectionRepository = fakeRepo,
            messageSerializer = MessageSerializer(),
            sequenceGenerator = SequenceGenerator(),
            scope = testScope,
            intervalMs = 20_000, // Default 20s — won't fire in most tests
            timeoutMs = 10_000
        )

        fakeLocationProvider = FakeLocationProvider()
        locationStreamer = LocationStreamer(
            locationProvider = fakeLocationProvider,
            locationRepository = FakeLocationRepository(),
            scope = testScope
        )

        supervisor = ConnectionSupervisor(
            connectionRepository = fakeRepo,
            sessionManager = fakeSession,
            authRepository = fakeAuth,
            deviceRepository = fakeDevice,
            networkObserver = fakeNetwork,
            reconnectPolicy = policy,
            heartbeatScheduler = heartbeatScheduler,
            locationStreamer = locationStreamer,
            audioStreamer = FakeAudioStreamer(),
            fileStreamer = createFakeFileStreamer(),
            scope = testScope
        )
    }

    /** Start supervisor and advance so collector is active. */
    private fun startAndActivate() {
        supervisor.start()
        testScope.advanceTimeBy(1)
    }

    /** Emit event and process it. Uses small time advance to avoid running heartbeat loop. */
    private fun emit(event: ConnectionEvent) {
        fakeEvents.tryEmit(event)
        testScope.advanceTimeBy(1)
    }

    /** Drive to Ready state. */
    private fun driveToReady() {
        startAndActivate()
        emit(ConnectionEvent.Connected)
        emit(ConnectionEvent.Authenticated)
        emit(ConnectionEvent.Registered)
        assertEquals(ConnectionState.Ready, supervisor.state.value)
    }

    // ================================================================
    // Basic state transitions
    // ================================================================

    @Test
    fun `initial state is Disconnected`() {
        assertEquals(ConnectionState.Disconnected, supervisor.state.value)
    }

    @Test
    fun `Connected event transitions to Authenticating`() {
        startAndActivate()
        emit(ConnectionEvent.Connected)
        assertEquals(ConnectionState.Authenticating, supervisor.state.value)
    }

    @Test
    fun `full pipeline transitions correctly`() {
        driveToReady()
    }

    @Test
    fun `stop resets state to Disconnected`() {
        startAndActivate()
        emit(ConnectionEvent.Connected)
        supervisor.stop()
        assertEquals(ConnectionState.Disconnected, supervisor.state.value)
    }

    @Test
    fun `HeartbeatAck does not change state`() {
        driveToReady()
        emit(ConnectionEvent.HeartbeatAck)
        assertEquals(ConnectionState.Ready, supervisor.state.value)
    }

    // ================================================================
    // Auth failure — no reconnect
    // ================================================================

    @Test
    fun `auth error 401 does not trigger reconnect`() {
        startAndActivate()
        emit(ConnectionEvent.Error(401, "Unauthorized"))
        assertEquals(ConnectionState.Error("Unauthorized"), supervisor.state.value)
    }

    @Test
    fun `auth error 403 does not trigger reconnect`() {
        startAndActivate()
        emit(ConnectionEvent.Connected)
        emit(ConnectionEvent.Error(403, "Forbidden"))
        assertEquals(ConnectionState.Error("Forbidden"), supervisor.state.value)
    }

    // ================================================================
    // Transport failure — triggers reconnect
    // ================================================================

    @Test
    fun `transport error from Ready triggers reconnect`() {
        driveToReady()
        fakeSession.savedUrl = "ws://test"
        fakeSession.savedToken = "token"
        emit(ConnectionEvent.Error(0, "Connection reset"))

        val state = supervisor.state.value
        assert(state is ConnectionState.Reconnecting || state is ConnectionState.Error) {
            "Expected Reconnecting or Error but got $state"
        }
    }

    @Test
    fun `unexpected disconnect from Ready triggers reconnect`() {
        driveToReady()
        fakeSession.savedUrl = "ws://test"
        fakeSession.savedToken = "token"
        emit(ConnectionEvent.Disconnected)

        val state = supervisor.state.value
        assert(state is ConnectionState.Reconnecting || state is ConnectionState.Error) {
            "Expected reconnect state but got $state"
        }
    }

    // ================================================================
    // Manual disconnect — no reconnect
    // ================================================================

    @Test
    fun `user stop prevents reconnect on disconnect`() {
        driveToReady()
        supervisor.stop()
        testScope.advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, supervisor.state.value)
    }

    @Test
    fun `user stop cancels active reconnect`() {
        driveToReady()
        fakeSession.savedUrl = "ws://test"
        fakeSession.savedToken = "token"
        fakeAuth.shouldSucceed = false

        emit(ConnectionEvent.Error(0, "Transport error"))
        testScope.advanceTimeBy(5)

        supervisor.stop()
        testScope.advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, supervisor.state.value)
    }

    // ================================================================
    // Retry limit
    // ================================================================

    @Test
    fun `reconnect exhausted after max attempts`() = runTest {
        val events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
        val repo = FakeConnectionRepository(events).apply {
            connectShouldThrow = true // Fail connect instantly — no timeout wait
        }
        val session = FakeSessionManager().apply {
            savedUrl = "ws://test"
            savedToken = "token"
        }
        val policy = ReconnectPolicy(
            config = ReconnectConfig(initialDelayMs = 1, maxDelayMs = 5, maxAttempts = 2, jitterFactor = 0.0)
        )

        val hb = HeartbeatScheduler(repo, MessageSerializer(), SequenceGenerator(), this, 99999, 99999)
        val ls = LocationStreamer(FakeLocationProvider(), FakeLocationRepository(), this)
        val sup = ConnectionSupervisor(
            repo, session, FakeAuthRepository(), FakeDeviceRepository(),
            FakeNetworkObserver(), policy, hb, ls, FakeAudioStreamer(), createFakeFileStreamer(), this
        )
        sup.start()
        advanceUntilIdle()

        events.tryEmit(ConnectionEvent.Connected)
        advanceUntilIdle()
        events.tryEmit(ConnectionEvent.Authenticated)
        advanceUntilIdle()
        events.tryEmit(ConnectionEvent.Registered)
        advanceUntilIdle()

        // Now make connect fail for reconnection
        events.tryEmit(ConnectionEvent.Error(0, "transport"))
        advanceUntilIdle()

        // Advance past backoff delays (2 attempts × ~5ms each)
        advanceTimeBy(100)
        advanceUntilIdle()

        val state = sup.state.value
        assert(state is ConnectionState.Error) {
            "Expected Error after exhausting retries, got $state"
        }
        sup.stop()
    }

    @Test
    fun `no reconnect when session is missing`() = runTest {
        val events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
        val repo = FakeConnectionRepository(events)
        val session = FakeSessionManager() // No URL/token saved
        val policy = ReconnectPolicy(
            config = ReconnectConfig(initialDelayMs = 1, maxDelayMs = 5, maxAttempts = 2, jitterFactor = 0.0)
        )

        val hb = HeartbeatScheduler(repo, MessageSerializer(), SequenceGenerator(), this, 99999, 99999)
        val ls = LocationStreamer(FakeLocationProvider(), FakeLocationRepository(), this)
        val sup = ConnectionSupervisor(
            repo, session, FakeAuthRepository(), FakeDeviceRepository(),
            FakeNetworkObserver(), policy, hb, ls, FakeAudioStreamer(), createFakeFileStreamer(), this
        )
        sup.start()
        advanceUntilIdle()

        events.tryEmit(ConnectionEvent.Connected)
        advanceUntilIdle()
        events.tryEmit(ConnectionEvent.Authenticated)
        advanceUntilIdle()
        events.tryEmit(ConnectionEvent.Registered)
        advanceUntilIdle()

        events.tryEmit(ConnectionEvent.Error(0, "transport"))
        advanceUntilIdle()
        advanceTimeBy(100)
        advanceUntilIdle()

        val state = sup.state.value
        assert(state is ConnectionState.Error) {
            "Expected Error when no session, got $state"
        }
        sup.stop()
    }

    // ================================================================
    // Network observation
    // ================================================================

    @Test
    fun `reconnect waits for network when unavailable`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
        val repo = FakeConnectionRepository(events)
        val session = FakeSessionManager().apply {
            savedUrl = "ws://test"
            savedToken = "token"
        }
        val auth = FakeAuthRepository().apply { shouldSucceed = false }
        val network = FakeNetworkObserver().apply { _isAvailable.value = false }
        val policy = ReconnectPolicy(
            config = ReconnectConfig(initialDelayMs = 1, maxAttempts = 1, jitterFactor = 0.0)
        )

        val hb = HeartbeatScheduler(repo, MessageSerializer(), SequenceGenerator(), scope, 99999, 99999)
        val ls = LocationStreamer(FakeLocationProvider(), FakeLocationRepository(), scope)
        val sup = ConnectionSupervisor(repo, session, auth, FakeDeviceRepository(), network, policy, hb, ls, FakeAudioStreamer(), createFakeFileStreamer(), scope)
        sup.start()

        events.tryEmit(ConnectionEvent.Connected)
        events.tryEmit(ConnectionEvent.Authenticated)
        events.tryEmit(ConnectionEvent.Registered)
        events.tryEmit(ConnectionEvent.Error(0, "transport"))

        delay(50)

        val state = sup.state.value
        assert(state is ConnectionState.Reconnecting) {
            "Expected Reconnecting while no network, got $state"
        }

        network._isAvailable.value = true
        delay(50)

        sup.stop()
        scope.cancel()
    }

    // ================================================================
    // Heartbeat integration
    // ================================================================

    @Test
    fun `heartbeat starts when state becomes Ready`() {
        startAndActivate()
        assertFalse(heartbeatScheduler.isRunning)

        emit(ConnectionEvent.Connected)
        emit(ConnectionEvent.Authenticated)
        emit(ConnectionEvent.Registered)

        assertTrue(heartbeatScheduler.isRunning)
    }

    @Test
    fun `heartbeat stops on disconnect`() {
        driveToReady()
        assertTrue(heartbeatScheduler.isRunning)

        emit(ConnectionEvent.Disconnected)
        assertFalse(heartbeatScheduler.isRunning)
    }

    @Test
    fun `heartbeat stops on error`() {
        driveToReady()
        assertTrue(heartbeatScheduler.isRunning)

        emit(ConnectionEvent.Error(401, "Unauthorized"))
        assertFalse(heartbeatScheduler.isRunning)
    }

    @Test
    fun `heartbeat stops on user stop`() {
        driveToReady()
        assertTrue(heartbeatScheduler.isRunning)

        supervisor.stop()
        assertFalse(heartbeatScheduler.isRunning)
    }

    @Test
    fun `HeartbeatAck is forwarded to scheduler`() {
        driveToReady()
        assertEquals(0L, heartbeatScheduler.lastAckTimestamp)

        emit(ConnectionEvent.HeartbeatAck)
        assertTrue(heartbeatScheduler.lastAckTimestamp > 0)
    }

    // ================================================================
    // Location integration
    // ================================================================

    @Test
    fun `location starts when state becomes Ready with permission`() {
        locationStreamer.hasPermission = true
        driveToReady()
        assertTrue(fakeLocationProvider.isActive)
    }

    @Test
    fun `location does not start without permission`() {
        locationStreamer.hasPermission = false
        driveToReady()
        assertFalse(fakeLocationProvider.isActive)
    }

    @Test
    fun `location pauses on disconnect`() {
        locationStreamer.hasPermission = true
        driveToReady()
        assertTrue(fakeLocationProvider.isActive)

        emit(ConnectionEvent.Disconnected)
        assertFalse(fakeLocationProvider.isActive)
    }

    @Test
    fun `location pauses on error`() {
        locationStreamer.hasPermission = true
        driveToReady()
        assertTrue(fakeLocationProvider.isActive)

        emit(ConnectionEvent.Error(0, "transport"))
        assertFalse(fakeLocationProvider.isActive)
    }

    @Test
    fun `location stops on user stop`() {
        locationStreamer.hasPermission = true
        driveToReady()
        assertTrue(fakeLocationProvider.isActive)

        supervisor.stop()
        assertFalse(fakeLocationProvider.isActive)
    }

    @Test
    fun `StartAudio event invokes onStartAudioRequested callback`() {
        startAndActivate()
        var requestedSeq: Long? = null
        var requestedDev: String? = null
        var requestedStream: String? = null
        supervisor.onStartAudioRequested = { seq, dev, streamId, _ ->
            requestedSeq = seq
            requestedDev = dev
            requestedStream = streamId
        }

        emit(ConnectionEvent.StartAudio(sequence = 99L, deviceId = "HOST-001", streamId = "test-uuid"))

        assertEquals(99L, requestedSeq)
        assertEquals("HOST-001", requestedDev)
        assertEquals("test-uuid", requestedStream)
    }

    @Test
    fun `StopAudio event invokes onStopAudioRequested callback`() {
        startAndActivate()
        var requestedSeq: Long? = null
        var requestedDev: String? = null
        var requestedStream: String? = null
        supervisor.onStopAudioRequested = { seq, dev, streamId ->
            requestedSeq = seq
            requestedDev = dev
            requestedStream = streamId
        }

        emit(ConnectionEvent.StopAudio(sequence = 100L, deviceId = "HOST-001", streamId = "test-uuid"))

        assertEquals(100L, requestedSeq)
        assertEquals("HOST-001", requestedDev)
        assertEquals("test-uuid", requestedStream)
    }

    @Test
    fun `forceReconnect attempts immediate reconnection`() {
        startAndActivate()
        fakeSession.saveServerUrl("ws://localhost")
        fakeSession.saveToken("test-token")

        supervisor.forceReconnect()
        testScope.advanceTimeBy(1)

        val state = supervisor.state.value
        assertTrue(state is ConnectionState.Reconnecting)
        supervisor.stop()
    }
}

// ================================================================
// Test fakes
// ================================================================

internal class FakeConnectionRepository(
    private val fakeEvents: MutableSharedFlow<ConnectionEvent>
) : ConnectionRepository {
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
    override val events: SharedFlow<ConnectionEvent> = fakeEvents
    var connectShouldThrow = false
    override suspend fun connect(serverUrl: String) {
        if (connectShouldThrow) throw Exception("Connection failed")
    }
    override suspend fun disconnect() {}
    override fun sendText(message: String): Boolean = true
    override fun sendBinary(data: ByteArray): Boolean = true
}

internal class FakeSessionManager : SessionManager {
    var savedUrl: String? = null
    var savedToken: String? = null
    var isApproved: Boolean = true
    override fun saveToken(token: String) { savedToken = token }
    override fun getToken(): String? = savedToken
    override fun clearToken() { savedToken = null }
    override fun saveServerUrl(url: String) { savedUrl = url }
    override fun getServerUrl(): String? = savedUrl
    override fun setEnrollmentApproved(approved: Boolean) { isApproved = approved }
    override fun isEnrollmentApproved(): Boolean = isApproved
    override fun hasSession(): Boolean = savedToken != null && savedUrl != null
    override fun clear() { savedToken = null; savedUrl = null }
}

internal class FakeAuthRepository : AuthRepository {
    var shouldSucceed = true
    override suspend fun authenticate(token: String): Result<Boolean> {
        return if (shouldSucceed) Result.success(true) else Result.failure(Exception("Auth failed"))
    }
}

internal class FakeDeviceRepository : DeviceRepository {
    var shouldSucceed = true
    override suspend fun register(device: DeviceInfo): Result<Boolean> {
        return if (shouldSucceed) Result.success(true) else Result.failure(Exception("Register failed"))
    }
    override fun getDeviceInfo(): DeviceInfo = DeviceInfo("test-id", "Test Device", "1.0", "TestModel")
}

internal class FakeNetworkObserver : NetworkObserver {
    val _isAvailable = MutableStateFlow(true)
    override val isAvailable: StateFlow<Boolean> = _isAvailable
    override fun start() {}
    override fun stop() {}
}

/**
 * Fake AudioStreamer for ConnectionSupervisor tests.
 * Tracks lifecycle calls without requiring real audio infrastructure.
 */
internal class FakeAudioStreamer : AudioStreamer(
    audioRepository = FakeAudioRepository(),
    pipeline = FakePipeline(),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
) {
    init {
        hasPermission = true
    }
}

private class FakeAudioRepository : com.sentinel.host.data.repository.AudioRepositoryImpl(
    pipeline = FakePipeline(),
    connectionRepository = object : ConnectionRepository {
        override val state = MutableStateFlow(ConnectionState.Disconnected)
        override val events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
        override suspend fun connect(serverUrl: String) {}
        override suspend fun disconnect() {}
        override fun sendText(message: String) = true
        override fun sendBinary(data: ByteArray) = true
    }
)

private class FakePipeline : com.sentinel.host.data.audio.AudioPipeline(
    recorder = object : com.sentinel.host.domain.audio.AudioRecorder {
        override val isRecording = false
        override fun start() = true
        override fun stop() {}
        override fun read(buffer: ShortArray, offset: Int, size: Int) = -1
        override fun close() {}
    },
    encoder = object : com.sentinel.host.domain.audio.OpusEncoder {
        override fun encode(pcm: ShortArray, frameSize: Int, output: ByteArray, maxOutput: Int) = -1
        override fun close() {}
    },
    testDispatcher = Dispatchers.Unconfined
)

internal fun createFakeFileStreamer(): FileStreamer {
    return FileStreamer(
        fileRepository = object : FileRepository {
            override fun listFiles(path: String): List<FileItem> = emptyList()
            override fun getFileMetadata(path: String): FileItem? = null
            override fun openFile(path: String, offset: Long): kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.emptyFlow()
        },
        connectionRepository = object : ConnectionRepository {
            override val state = MutableStateFlow(ConnectionState.Disconnected)
            override val events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
            override suspend fun connect(serverUrl: String) {}
            override suspend fun disconnect() {}
            override fun sendText(message: String) = true
            override fun sendBinary(data: ByteArray) = true
        },
        messageSerializer = MessageSerializer(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )
}
