package com.sentinel.admin.domain.model

/**
 * Domain events representing real-time device state changes
 * received via WebSocket DEVICE_UPDATE messages.
 *
 * Each variant carries only the fields that changed.
 * The repository applies these as incremental patches.
 */
sealed interface DeviceUpdateEvent {
    val deviceId: String

    data class DeviceConnected(
        override val deviceId: String,
        val deviceName: String? = null,
        val appVersion: String? = null,
        val model: String? = null
    ) : DeviceUpdateEvent

    data class DeviceDisconnected(
        override val deviceId: String,
        val model: String? = null
    ) : DeviceUpdateEvent

    data class HeartbeatReceived(
        override val deviceId: String,
        val timestamp: String? = null,
        val model: String? = null
    ) : DeviceUpdateEvent

    data class LocationUpdated(
        override val deviceId: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double? = null,
        val battery: Int? = null,
        val network: String? = null,
        val model: String? = null,
        val timestamp: String? = null
    ) : DeviceUpdateEvent

    data class BatteryUpdated(
        override val deviceId: String,
        val battery: Int,
        val model: String? = null
    ) : DeviceUpdateEvent

    data class NetworkUpdated(
        override val deviceId: String,
        val network: String,
        val model: String? = null
    ) : DeviceUpdateEvent

    data class MetadataUpdated(
        override val deviceId: String,
        val deviceName: String? = null,
        val appVersion: String? = null,
        val model: String? = null
    ) : DeviceUpdateEvent

    data class EmergencySos(
        override val deviceId: String,
        val triggerReason: String,
        val impactGForce: Double,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracy: Double? = null,
        val battery: Int? = null,
        val model: String? = null,
        val timestamp: String? = null
    ) : DeviceUpdateEvent
}
