package com.sentinel.admin.ui.login

import com.sentinel.admin.domain.session.SessionPreferences
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.domain.repository.AuthRepository
import com.sentinel.admin.domain.supervisor.ConnectionSupervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for [LoginViewModel].
 *
 * Uses test doubles for AdminSupervisor, AuthRepository, SessionPreferences.
 *
 * Covers:
 * - Input validation (URL, token)
 * - Connect flow with credential persistence
 * - Disconnect with session clearing
 * - Remember Me persistence
 * - Auto-login on launch
 * - State transitions
 * - Connect button enable/disable logic
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    // ============================================================
    // Test doubles
    // ============================================================

    /**
     * Fake supervisor that doesn't need real dependencies.
     * Only exposes connectionState, start(), stop().
     */
    private class FakeAdminSupervisor : ConnectionSupervisor {
        private val _testConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = _testConnectionState

        var startCalled = false
        var stopCalled = false
        var lastServerUrl: String? = null

        override fun start(serverUrl: String) {
            startCalled = true
            lastServerUrl = serverUrl
        }

        override fun stop() {
            stopCalled = true
            _testConnectionState.value = ConnectionState.Disconnected
        }

        var ensureConnectedCalled = false

        override fun ensureConnected() {
            ensureConnectedCalled = true
        }

        fun setState(state: ConnectionState) {
            _testConnectionState.value = state
        }
    }

    private class FakeAuthRepository : AuthRepository {
        var storedToken: String? = null
        override fun getToken(): String? = storedToken
        override fun saveToken(token: String) { storedToken = token }
        override fun clearToken() { storedToken = null }
    }

    private class FakeSessionPreferences : SessionPreferences {
        var storedUrl: String? = null
        var storedRememberMe: Boolean = false

        override var serverUrl: String?
            get() = storedUrl
            set(value) { storedUrl = value }

        override var rememberMe: Boolean
            get() = storedRememberMe
            set(value) { storedRememberMe = value }

        override fun clear() {
            storedUrl = null
            storedRememberMe = false
        }
    }

    // ============================================================
    // Setup
    // ============================================================

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeSupervisor: FakeAdminSupervisor
    private lateinit var fakeAuthRepo: FakeAuthRepository
    private lateinit var fakeSessionPrefs: FakeSessionPreferences
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeSupervisor = FakeAdminSupervisor()
        fakeAuthRepo = FakeAuthRepository()
        fakeSessionPrefs = FakeSessionPreferences()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LoginViewModel {
        return LoginViewModel(fakeSupervisor, fakeAuthRepo, fakeSessionPrefs)
    }

    // ============================================================
    // Validation
    // ============================================================

    @Test
    fun `validateServerUrl rejects blank`() {
        viewModel = createViewModel()
        assertNotNull(viewModel.validateServerUrl(""))
        assertNotNull(viewModel.validateServerUrl("   "))
    }

    @Test
    fun `validateServerUrl rejects non-ws scheme`() {
        viewModel = createViewModel()
        assertNotNull(viewModel.validateServerUrl("http://example.com"))
        assertNotNull(viewModel.validateServerUrl("https://example.com"))
    }

    @Test
    fun `validateServerUrl accepts ws and wss`() {
        viewModel = createViewModel()
        assertNull(viewModel.validateServerUrl("ws://example.com/ws"))
        assertNull(viewModel.validateServerUrl("wss://example.com/ws"))
    }

    @Test
    fun `validateToken rejects blank`() {
        viewModel = createViewModel()
        assertNotNull(viewModel.validateToken(""))
        assertNotNull(viewModel.validateToken("   "))
    }

    @Test
    fun `validateToken rejects too short`() {
        viewModel = createViewModel()
        assertNotNull(viewModel.validateToken("short"))
    }

    @Test
    fun `validateToken accepts valid token`() {
        viewModel = createViewModel()
        assertNull(viewModel.validateToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
    }

    @Test
    fun `validateToken rejects revoked token`() {
        viewModel = createViewModel()
        val error = viewModel.validateToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJkZXZpY2VfaWQiOiJBRE1JTi0wMDEiLCJpc3MiOiJwcm9qZWN0LXNlbnRpbmVsIiwic3ViIjoiQURNSU4tMDAxIiwiZXhwIjoyMTA3OTU5NjEwLCJpYXQiOjE3OTI1OTk2MTB9.aYtZD61xgGvPGds18AaXT7iALu57JlyoKmFTWsK7RXs")
        assertEquals("This administrator token has been revoked", error)
    }

    // ============================================================
    // Connect
    // ============================================================

    @Test
    fun `connect with valid inputs starts supervisor`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onServerUrlChanged("wss://test.example.com/ws")
        viewModel.onTokenChanged("valid-jwt-token-here")
        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(fakeSupervisor.startCalled)
        assertEquals("wss://test.example.com/ws", fakeSupervisor.lastServerUrl)
    }

    @Test
    fun `connect persists credentials`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onServerUrlChanged("wss://test.com/ws")
        viewModel.onTokenChanged("saved-token-value")
        viewModel.onRememberMeChanged(true)
        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("saved-token-value", fakeAuthRepo.storedToken)
        assertEquals("wss://test.com/ws", fakeSessionPrefs.storedUrl)
        assertTrue(fakeSessionPrefs.storedRememberMe)
    }

    @Test
    fun `connect with invalid URL does not start supervisor`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onServerUrlChanged("http://invalid")
        viewModel.onTokenChanged("valid-jwt-token-here")
        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(fakeSupervisor.startCalled)
        assertNotNull(viewModel.uiState.value.serverUrlError)
    }

    @Test
    fun `connect with empty inputs shows validation errors`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(fakeSupervisor.startCalled)
        assertNotNull(viewModel.uiState.value.serverUrlError)
        assertNotNull(viewModel.uiState.value.tokenError)
    }

    // ============================================================
    // Disconnect
    // ============================================================

    @Test
    fun `disconnect stops supervisor`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(fakeSupervisor.stopCalled)
    }

    @Test
    fun `disconnect without remember me clears credentials`() = runTest(testDispatcher) {
        fakeAuthRepo.storedToken = "existing-token"
        fakeSessionPrefs.storedUrl = "wss://test.com/ws"
        fakeSessionPrefs.storedRememberMe = false

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRememberMeChanged(false)
        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(fakeAuthRepo.storedToken)
        assertNull(fakeSessionPrefs.storedUrl)
    }

    @Test
    fun `disconnect with remember me keeps credentials`() = runTest(testDispatcher) {
        fakeAuthRepo.storedToken = "existing-token"
        fakeSessionPrefs.storedUrl = "wss://test.com/ws"

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRememberMeChanged(true)
        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("existing-token", fakeAuthRepo.storedToken)
        assertEquals("wss://test.com/ws", fakeSessionPrefs.storedUrl)
    }

    // ============================================================
    // Auto-login
    // ============================================================

    @Test
    fun `auto-login when remember me enabled with saved credentials`() = runTest(testDispatcher) {
        fakeAuthRepo.storedToken = "saved-token"
        fakeSessionPrefs.storedUrl = "wss://saved.com/ws"
        fakeSessionPrefs.storedRememberMe = true

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Should auto-connect", fakeSupervisor.startCalled)
        assertEquals("wss://saved.com/ws", fakeSupervisor.lastServerUrl)
    }

    @Test
    fun `no auto-login when remember me disabled`() = runTest(testDispatcher) {
        fakeAuthRepo.storedToken = "saved-token"
        fakeSessionPrefs.storedUrl = "wss://saved.com/ws"
        fakeSessionPrefs.storedRememberMe = false

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Should not auto-connect", fakeSupervisor.startCalled)
        // But fields should be pre-filled
        assertEquals("wss://saved.com/ws", viewModel.uiState.value.serverUrl)
        assertEquals("saved-token", viewModel.uiState.value.token)
    }

    @Test
    fun `no auto-login with missing credentials`() = runTest(testDispatcher) {
        fakeSessionPrefs.storedRememberMe = true
        // No token or URL saved

        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(fakeSupervisor.startCalled)
    }

    // ============================================================
    // State transitions
    // ============================================================

    @Test
    fun `connection state updates UI state`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        fakeSupervisor.setState(ConnectionState.Connecting)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConnecting)

        fakeSupervisor.setState(ConnectionState.Authenticating)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConnecting)

        fakeSupervisor.setState(ConnectionState.Ready)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isConnecting)
        assertEquals(ConnectionState.Ready, viewModel.uiState.value.connectionState)
    }

    @Test
    fun `error state shows error message`() = runTest(testDispatcher) {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        fakeSupervisor.setState(ConnectionState.Error("Auth failed"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Auth failed", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isConnecting)
    }

    // ============================================================
    // UI State computed properties
    // ============================================================

    @Test
    fun `inputs enabled when disconnected`() {
        val state = LoginUiState(connectionState = ConnectionState.Disconnected)
        assertTrue(state.inputsEnabled)
    }

    @Test
    fun `inputs enabled when error`() {
        val state = LoginUiState(connectionState = ConnectionState.Error("fail"))
        assertTrue(state.inputsEnabled)
    }

    @Test
    fun `inputs disabled when connecting`() {
        val state = LoginUiState(connectionState = ConnectionState.Connecting)
        assertFalse(state.inputsEnabled)
    }

    @Test
    fun `inputs disabled when ready`() {
        val state = LoginUiState(connectionState = ConnectionState.Ready)
        assertFalse(state.inputsEnabled)
    }

    @Test
    fun `canDisconnect false when disconnected`() {
        val state = LoginUiState(connectionState = ConnectionState.Disconnected)
        assertFalse(state.canDisconnect)
    }

    @Test
    fun `canDisconnect true when ready`() {
        val state = LoginUiState(connectionState = ConnectionState.Ready)
        assertTrue(state.canDisconnect)
    }

    @Test
    fun `statusText for all states`() {
        assertEquals("Disconnected", LoginUiState(connectionState = ConnectionState.Disconnected).statusText)
        assertEquals("Connecting…", LoginUiState(connectionState = ConnectionState.Connecting).statusText)
        assertEquals("Authenticating…", LoginUiState(connectionState = ConnectionState.Authenticating).statusText)
        assertEquals("Connected", LoginUiState(connectionState = ConnectionState.Ready).statusText)
        assertTrue(LoginUiState(connectionState = ConnectionState.Reconnecting(2)).statusText.contains("2"))
        assertTrue(LoginUiState(connectionState = ConnectionState.Error("timeout")).statusText.contains("timeout"))
    }
}
