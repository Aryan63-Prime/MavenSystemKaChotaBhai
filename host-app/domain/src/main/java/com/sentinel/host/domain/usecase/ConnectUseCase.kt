package com.sentinel.host.domain.usecase

import com.sentinel.host.domain.model.ConnectionEvent
import com.sentinel.host.domain.model.ConnectionState
import com.sentinel.host.domain.repository.AuthRepository
import com.sentinel.host.domain.repository.ConnectionRepository
import com.sentinel.host.domain.repository.DeviceRepository
import com.sentinel.host.domain.session.SessionManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Orchestrates the full connection pipeline:
 *
 *   Connect → Authenticate → Register → Ready
 *
 * Saves session before connecting.
 * Does NOT handle reconnect (Sprint A6).
 * Does NOT start heartbeat (Sprint A4).
 */
class ConnectUseCase(
    private val connectionRepository: ConnectionRepository,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val sessionManager: SessionManager
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val AUTH_TIMEOUT_MS = 10_000L
        private const val REGISTER_TIMEOUT_MS = 10_000L
    }

    /**
     * Runs the connection pipeline.
     * Suspends until Ready or returns failure.
     */
    suspend fun execute(serverUrl: String, token: String): Result<Unit> {
        // Save session for auto-login
        sessionManager.saveToken(token)
        sessionManager.saveServerUrl(serverUrl)

        // Step 1: Connect WebSocket
        if (connectionRepository.state.value !is ConnectionState.Connected &&
            connectionRepository.state.value !is ConnectionState.Ready &&
            connectionRepository.state.value !is ConnectionState.Authenticating &&
            connectionRepository.state.value !is ConnectionState.Registering) {
            connectionRepository.connect(serverUrl)

            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    connectionRepository.events.first { it is ConnectionEvent.Connected }
                }
            } catch (e: TimeoutCancellationException) {
                return Result.failure(ConnectException("Connection timeout"))
            }
        }

        // Resolve device info first (resets cloned token if hardware mismatch detected)
        val deviceInfo = deviceRepository.getDeviceInfo()
        val activeToken = sessionManager.getToken() ?: token

        // Step 2: Authenticate
        val authResult = authRepository.authenticate(activeToken)
        if (authResult.isFailure) {
            return Result.failure(
                authResult.exceptionOrNull() ?: ConnectException("Authentication failed")
            )
        }

        // Step 3: Register device
        val registerResult = deviceRepository.register(deviceInfo)
        if (registerResult.isFailure) {
            return Result.failure(
                registerResult.exceptionOrNull() ?: ConnectException("Registration failed")
            )
        }

        return Result.success(Unit)
    }
}

class ConnectException(message: String) : Exception(message)
