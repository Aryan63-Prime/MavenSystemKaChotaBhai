package com.sentinel.host.data.repository

import android.os.Build
import com.sentinel.host.data.remote.SequenceGenerator
import com.sentinel.host.data.remote.protocol.MessageSerializer
import com.sentinel.host.domain.model.ConnectionEvent
import com.sentinel.host.domain.model.DeviceInfo
import com.sentinel.host.domain.repository.ConnectionRepository
import com.sentinel.host.domain.repository.DeviceRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Sends REGISTER message and awaits REGISTER_ACK event with timeout.
 * Collects device info from Android system properties.
 */
class DeviceRepositoryImpl(
    private val connectionRepository: ConnectionRepository,
    private val messageSerializer: MessageSerializer,
    private val sequenceGenerator: SequenceGenerator,
    private val sessionManager: com.sentinel.host.domain.session.SessionManager,
    private val appVersion: String,
    private val hardwareInfoProvider: com.sentinel.host.data.device.HardwareInfoProvider? = null
) : DeviceRepository {

    companion object {
        private const val REGISTER_TIMEOUT_MS = 10_000L
    }

    override suspend fun register(device: DeviceInfo): Result<Boolean> {
        val json = messageSerializer.serializeRegister(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            appVersion = device.appVersion,
            model = device.model,
            sequence = sequenceGenerator.next(),
            fcmToken = device.fcmToken.ifBlank { sessionManager.getFcmToken() }
        )

        if (!connectionRepository.sendText(json)) {
            return Result.failure(Exception("Failed to send REGISTER message"))
        }

        return try {
            withTimeout(REGISTER_TIMEOUT_MS) {
                val event = connectionRepository.events.first { event ->
                    event is ConnectionEvent.Registered || event is ConnectionEvent.Error
                }
                when (event) {
                    is ConnectionEvent.Registered -> {
                        event.token?.takeIf { it.isNotBlank() }?.let { newToken ->
                            sessionManager.saveToken(newToken)
                        }
                        Result.success(true)
                    }
                    is ConnectionEvent.Error -> Result.failure(
                        RegistrationException(event.code, event.message)
                    )
                    else -> Result.failure(Exception("Unexpected event"))
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(RegistrationException(0, "Registration timeout"))
        }
    }

    override fun getDeviceInfo(): DeviceInfo {
        val hw = hardwareInfoProvider?.getHardwareInfo()

        val manufacturer = hw?.manufacturer ?: Build.MANUFACTURER ?: "Unknown"
        val brand = hw?.brand ?: Build.BRAND ?: manufacturer
        val model = hw?.model ?: Build.MODEL ?: "Unknown"
        val callsign = hw?.hardwareCallsign ?: run {
            val rawSerial = Build.SERIAL?.takeIf { !it.equals(Build.UNKNOWN, ignoreCase = true) && it.isNotBlank() }
            val shortId = rawSerial?.takeLast(4)?.uppercase()?.padStart(4, '0')
                ?: (Build.FINGERPRINT + Build.MODEL + Build.BOARD + Build.DEVICE)
                    .hashCode().toUInt().toString(16).uppercase().padStart(4, '0').takeLast(4)
            val oem = manufacturer.uppercase().filter { it.isLetter() }.take(4).ifBlank { "UNIT" }
            "HOST-$oem-$shortId"
        }

        // Use the hardware callsign (e.g. HOST-VIVO-2D2C) as the distinct deviceId.
        // If a custom non-generic token is provisioned (not HOST-001), use that instead.
        val hwDeviceId = "HOST-001-${callsign.removePrefix("HOST-")}"
        val jwtDeviceId = extractDeviceIdFromToken()
        val deviceId = if (!jwtDeviceId.isNullOrBlank() && jwtDeviceId != "HOST-001") {
            if (jwtDeviceId.startsWith("HOST-001-") && jwtDeviceId != hwDeviceId) {
                // The saved JWT belonged to a cloned device identity; prioritize this hardware's real ID
                // and reset the cloned token back to default bootstrap token
                try {
                    sessionManager.saveToken(com.sentinel.host.data.session.SessionManagerImpl.DEFAULT_JWT_TOKEN)
                } catch (_: Exception) {}
                hwDeviceId
            } else {
                jwtDeviceId
            }
        } else {
            hwDeviceId
        }

        // Hardware-level device name: e.g. "Vivo I2401 (HOST-VIVO-CFAE)"
        val brandDisplay = brand.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val deviceName = "$brandDisplay $model ($callsign)"

        return DeviceInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            model = model,
            appVersion = appVersion,
            hardwareId = hw?.hardwareId ?: "",
            hardwareBrand = brand,
            hardwareBoard = hw?.board ?: Build.BOARD ?: "",
            androidVersion = hw?.androidVersion ?: Build.VERSION.RELEASE ?: "",
            sdkInt = hw?.sdkInt ?: Build.VERSION.SDK_INT,
            callsign = callsign,
            fcmToken = sessionManager.getFcmToken() ?: ""
        )
    }

    /**
     * Extracts the device_id claim from the saved JWT token.
     * JWT is base64-encoded: header.payload.signature
     * We decode the payload to get the device_id claim.
     */
    private fun extractDeviceIdFromToken(): String? {
        return try {
            val token = sessionManager.getToken() ?: return null
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payload = String(
                android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
                Charsets.UTF_8
            )
            // Simple JSON parse for device_id
            val regex = """"device_id"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(payload)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }
}

class RegistrationException(val code: Int, override val message: String) : Exception(message)
