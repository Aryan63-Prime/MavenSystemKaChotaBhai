package com.sentinel.admin.domain.model

/**
 * Location data for a monitored device.
 *
 * Nested inside [Device.latestLocation] from the REST API response.
 *
 * @property deviceId Device that reported this location.
 * @property latitude GPS latitude.
 * @property longitude GPS longitude.
 * @property accuracy Location accuracy in meters.
 * @property battery Battery level (0-100).
 * @property network Network type (e.g., "5G", "WiFi").
 * @property recordedAt ISO-8601 timestamp of when the location was recorded.
 */
data class DeviceLocation(
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val battery: Int,
    val network: String,
    val recordedAt: String,
    val hasReportedFix: Boolean = true,
    val receivedAt: String? = null
)
