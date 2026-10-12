package com.sentinel.host.data.repository

import com.sentinel.host.data.remote.SequenceGenerator
import com.sentinel.host.data.remote.protocol.MessageSerializer
import com.sentinel.host.domain.model.ConnectionEvent
import com.sentinel.host.domain.model.ConnectionState
import com.sentinel.host.domain.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import com.sentinel.host.domain.session.SessionManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepositoryImplTest {

    private lateinit var scope: CoroutineScope
    private lateinit var authRepo: AuthRepositoryImpl
    private lateinit var fakeEvents: MutableSharedFlow<ConnectionEvent>
    private lateinit var fakeRepo: FakeConnectionRepository
    private lateinit var serializer: MessageSerializer
    private lateinit var sequenceGen: SequenceGenerator

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fakeEvents = MutableSharedFlow(extraBufferCapacity = 64)
        fakeRepo = FakeConnectionRepository(fakeEvents)
        serializer = MessageSerializer()
        sequenceGen = SequenceGenerator()
        authRepo = AuthRepositoryImpl(fakeRepo, serializer, sequenceGen)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `authenticate succeeds on Authenticated event`() = runTest {
        // Simulate server responding with AUTH_ACK after a delay
        launch {
            fakeEvents.emit(ConnectionEvent.Authenticated)
        }

        val result = authRepo.authenticate("valid-token")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
    }

    @Test
    fun `authenticate fails on Error event`() = runTest {
        launch {
            fakeEvents.emit(ConnectionEvent.Error(401, "Unauthorized"))
        }

        val result = authRepo.authenticate("bad-token")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthenticationException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Unauthorized"))
    }

    @Test
    fun `authenticate fails when send returns false`() = runTest {
        fakeRepo.sendShouldFail = true

        val result = authRepo.authenticate("token")
        assertTrue(result.isFailure)
    }

    @Test
    fun `authenticate sends correct message type`() = runTest {
        launch {
            fakeEvents.emit(ConnectionEvent.Authenticated)
        }

        authRepo.authenticate("my-token")
        assertTrue(fakeRepo.lastSentText?.contains("\"type\":\"AUTH\"") == true)
        assertTrue(fakeRepo.lastSentText?.contains("\"token\":\"my-token\"") == true)
    }
}

class DeviceRepositoryImplTest {

    private lateinit var scope: CoroutineScope
    private lateinit var deviceRepo: DeviceRepositoryImpl
    private lateinit var fakeEvents: MutableSharedFlow<ConnectionEvent>
    private lateinit var fakeRepo: FakeConnectionRepository
    private lateinit var serializer: MessageSerializer
    private lateinit var sequenceGen: SequenceGenerator

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fakeEvents = MutableSharedFlow(extraBufferCapacity = 64)
        fakeRepo = FakeConnectionRepository(fakeEvents)
        serializer = MessageSerializer()
        sequenceGen = SequenceGenerator()
        deviceRepo = DeviceRepositoryImpl(fakeRepo, serializer, sequenceGen, FakeSessionManager(), "1.0.0-test")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `register succeeds on Registered event`() = runTest {
        launch {
            fakeEvents.emit(ConnectionEvent.Registered)
        }

        val device = deviceRepo.getDeviceInfo()
        val result = deviceRepo.register(device)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `register fails on Error event`() = runTest {
        launch {
            fakeEvents.emit(ConnectionEvent.Error(409, "Already registered"))
        }

        val device = deviceRepo.getDeviceInfo()
        val result = deviceRepo.register(device)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RegistrationException)
    }

    @Test
    fun `register sends correct message type`() = runTest {
        launch {
            fakeEvents.emit(ConnectionEvent.Registered)
        }

        val device = deviceRepo.getDeviceInfo()
        deviceRepo.register(device)
        assertTrue(fakeRepo.lastSentText?.contains("\"type\":\"REGISTER\"") == true)
    }

    @Test
    fun `getDeviceInfo returns non-empty values`() {
        val info = deviceRepo.getDeviceInfo()
        assertFalse(info.deviceId.isBlank())
        assertFalse(info.deviceName.isBlank())
        assertFalse(info.model.isBlank())
    }
}

/**
 * Fake ConnectionRepository for testing.
 */
internal class FakeConnectionRepository(
    private val fakeEvents: MutableSharedFlow<ConnectionEvent>
) : ConnectionRepository {
    override val state: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.Connected)
    override val events: SharedFlow<ConnectionEvent> = fakeEvents

    var lastSentText: String? = null
    var sendShouldFail = false

    override suspend fun connect(serverUrl: String) {}
    override suspend fun disconnect() {}

    override fun sendText(message: String): Boolean {
        if (sendShouldFail) return false
        lastSentText = message
        return true
    }

    override fun sendBinary(data: ByteArray): Boolean = true
}

internal class FakeSessionManager : SessionManager {
    private var token: String? = null
    private var serverUrl: String? = null
    private var enrollmentApproved: Boolean = false

    override fun saveToken(token: String) { this.token = token }
    override fun getToken(): String? = token
    override fun clearToken() { token = null }
    override fun saveServerUrl(url: String) { this.serverUrl = url }
    override fun getServerUrl(): String? = serverUrl
    override fun hasSession(): Boolean = token != null
    override fun clear() {
        token = null
        serverUrl = null
        enrollmentApproved = false
    }

    override fun setEnrollmentApproved(approved: Boolean) { enrollmentApproved = approved }
    override fun isEnrollmentApproved(): Boolean = enrollmentApproved
    override fun setCaptureApproval(approved: Boolean) { setEnrollmentApproved(approved) }
}

class CaptureApprovalTest {
    @Test
    fun `enrollment approval defaults to false and updates on explicit approval`() {
        val sessionMgr = FakeSessionManager()
        assertFalse(sessionMgr.isEnrollmentApproved())
        sessionMgr.setEnrollmentApproved(true)
        assertTrue(sessionMgr.isEnrollmentApproved())
        sessionMgr.clear()
        assertFalse(sessionMgr.isEnrollmentApproved())
    }
}


