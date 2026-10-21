package com.sentinel.admin.domain.model

/**
 * A monitored device as returned by the Admin REST API and realtime WebSocket feeds.
 * Supports hardware-level identity, deterministic callsign resolution, and multi-device segregation.
 */
data class Device(
    val deviceId: String,
    val connectionId: String,
    val authenticated: Boolean,
    val registered: Boolean,
    val registrationState: String,
    val heartbeatStatus: String,
    val connectedAt: String,
    val lastHeartbeat: String,
    val deviceName: String,
    val appVersion: String,
    val model: String,
    val latestLocation: DeviceLocation?,
    val callsign: String = "",
    val hardwareId: String = ""
) {
    /** Resolved callsign prioritizing explicit callsign, then hardware callsign extracted from deviceName. */
    val resolvedCallsign: String
        get() = callsign.ifBlank {
            Regex("""\((HOST\d*-[A-Za-z0-9_-]+)\)""").find(deviceName)?.groupValues?.get(1) ?: ""
        }

    /** Unique identity for UI rendering and tracking across sessions when devices share a deviceId token. */
    val uniqueKey: String
        get() = when {
            deviceId.isNotBlank() && deviceId != "HOST-001" && (deviceId.startsWith("HOST-") || deviceId.contains("-")) -> deviceId
            hardwareId.isNotBlank() && hardwareId != "unknown_hw" -> "${deviceId}_${hardwareId}"
            resolvedCallsign.isNotBlank() && resolvedCallsign.contains("-") -> "${deviceId}_${resolvedCallsign}"
            model.isNotBlank() && model != "Unknown" -> "${deviceId}_${model}"
            connectionId.isNotBlank() -> "${deviceId}_${connectionId}"
            else -> deviceId
        }

    /** Display identity: hardware fleet callsign (HOST-VIVO-CFAE...) if assigned, otherwise deviceId. */
    val displayId: String
        get() = resolvedCallsign.ifBlank { deviceId }
}
