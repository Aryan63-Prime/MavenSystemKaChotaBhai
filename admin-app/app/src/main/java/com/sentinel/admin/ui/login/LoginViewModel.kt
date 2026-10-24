package com.sentinel.admin.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentinel.admin.domain.session.SessionPreferences
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.domain.repository.AuthRepository
import com.sentinel.admin.domain.supervisor.ConnectionSupervisor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel for the Login screen.
 *
 * Responsibilities:
 * - Observes ConnectionState from AdminSupervisor
 * - Validates inputs before connecting
 * - Persists session via AuthRepository and SessionPreferences
 * - Handles auto-connect when Remember Me is enabled
 * - Exposes immutable [LoginUiState] to the Compose UI
 *
 * Does NOT own the connection. AdminSupervisor owns the lifecycle.
 * This ViewModel only dispatches start/stop commands.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val supervisor: ConnectionSupervisor,
    private val authRepository: AuthRepository,
    private val sessionPreferences: SessionPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        observeConnectionState()
        loadSavedSession()
    }

    // ============================================================
    // User actions
    // ============================================================

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, serverUrlError = null) }
    }

    fun onTokenChanged(token: String) {
        _uiState.update { it.copy(token = token, tokenError = null) }
    }

    fun onRememberMeChanged(checked: Boolean) {
        _uiState.update { it.copy(rememberMe = checked) }
    }

    /**
     * Validates inputs and initiates connection via AdminSupervisor.
     */
    fun connect() {
        val state = _uiState.value

        // Validate
        val urlError = validateServerUrl(state.serverUrl)
        val tokenError = validateToken(state.token)

        if (urlError != null || tokenError != null) {
            _uiState.update {
                it.copy(serverUrlError = urlError, tokenError = tokenError)
            }
            return
        }

        // Persist credentials
        authRepository.saveToken(state.token)
        sessionPreferences.serverUrl = state.serverUrl
        sessionPreferences.rememberMe = state.rememberMe

        // Clear error and start connection
        _uiState.update { it.copy(errorMessage = null, isConnecting = true) }
        supervisor.start(state.serverUrl)
    }

    /**
     * Disconnects and clears session if Remember Me is off.
     */
    fun disconnect() {
        supervisor.stop()

        val remember = _uiState.value.rememberMe
        if (!remember) {
            authRepository.clearToken()
            sessionPreferences.clear()
            _uiState.update {
                it.copy(serverUrl = "", token = "", rememberMe = false)
            }
        }
    }

    // ============================================================
    // Internal
    // ============================================================

    private fun observeConnectionState() {
        supervisor.connectionState
            .onEach { connectionState ->
                _uiState.update { current ->
                    current.copy(
                        connectionState = connectionState,
                        isConnecting = connectionState is ConnectionState.Connecting ||
                                connectionState is ConnectionState.TransportConnected ||
                                connectionState is ConnectionState.Authenticating,
                        errorMessage = when (connectionState) {
                            is ConnectionState.Error -> connectionState.message
                            else -> null
                        }
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun loadSavedSession() {
        val savedUrl = sessionPreferences.serverUrl
        val savedToken = authRepository.getToken()
        val rememberMe = sessionPreferences.rememberMe

        if (savedUrl != null && savedToken != null) {
            _uiState.update {
                it.copy(
                    serverUrl = savedUrl,
                    token = savedToken,
                    rememberMe = rememberMe
                )
            }

            // Auto-connect if Remember Me was enabled
            if (rememberMe) {
                connect()
            }
        }
    }

    internal fun validateServerUrl(url: String): String? {
        if (url.isBlank()) return "Server URL is required"
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            return "URL must start with ws:// or wss://"
        }
        return null
    }

    internal fun validateToken(token: String): String? {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return "Token is required"
        if (trimmed.length < 10) return "Token appears too short"
        if (com.sentinel.admin.data.security.AdminCryptoVault.isTokenRevoked(trimmed)) {
            return "This administrator token has been revoked"
        }
        return null
    }
}
