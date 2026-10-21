package com.sentinel.admin.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTO for GET /devices response wrapper.
 */
@JsonClass(generateAdapter = true)
data class DevicesResponse(
    @Json(name = "devices") val devices: List<DeviceDto>
)

/**
 * DTO for a single device from GET /devices and GET /devices/{deviceId}.
 * Maps to the backend JSON response. Not exposed outside :data.
 */
@JsonClass(generateAdapter = true)
data class DeviceDto(
    @Json(name = "deviceId") val deviceId: String = "",
    @Json(name = "connectionId") val connectionId: String = "",
    @Json(name = "authenticated") val authenticated: Boolean = false,
    @Json(name = "registered") val registered: Boolean = false,
    @Json(name = "registrationState") val registrationState: String = "",
    @Json(name = "heartbeatStatus") val heartbeatStatus: String = "",
    @Json(name = "connectedAt") val connectedAt: String = "",
    @Json(name = "lastHeartbeat") val lastHeartbeat: String = "",
    @Json(name = "deviceName") val deviceName: String = "",
    @Json(name = "appVersion") val appVersion: String = "",
    @Json(name = "model") val model: String = "",
    @Json(name = "latestLocation") val latestLocation: DeviceLocationDto? = null
)

/**
 * DTO for nested location data within a device response.
 */
@JsonClass(generateAdapter = true)
data class DeviceLocationDto(
    @Json(name = "deviceId") val deviceId: String,
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "accuracy") val accuracy: Double,
    @Json(name = "battery") val battery: Int,
    @Json(name = "network") val network: String,
    @Json(name = "recordedAt") val recordedAt: String
)

/**
 * DTO for POST /devices/{deviceId}/wake response.
 */
@JsonClass(generateAdapter = true)
data class WakeResponseDto(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "deviceId") val deviceId: String? = null,
    @Json(name = "messageId") val messageId: String? = null
)

/**
 * DTO for GET /devices/{deviceId}/route response.
 */
@JsonClass(generateAdapter = true)
data class RouteHistoryResponse(
    @Json(name = "deviceId") val deviceId: String = "",
    @Json(name = "points") val points: List<DeviceLocationDto> = emptyList()
)

/**
 * DTO for GET /devices/{deviceId}/battery-history response.
 */
@JsonClass(generateAdapter = true)
data class BatteryHistoryDto(
    @Json(name = "batteryPercent") val batteryPercent: Int = 0,
    @Json(name = "batteryTempC") val batteryTempC: Double = 25.0,
    @Json(name = "isCharging") val isCharging: Boolean = false,
    @Json(name = "thermalStatus") val thermalStatus: String = "Nominal",
    @Json(name = "recordedAt") val recordedAt: String = ""
)

@JsonClass(generateAdapter = true)
data class BatteryHistoryResponse(
    @Json(name = "deviceId") val deviceId: String = "",
    @Json(name = "history") val history: List<BatteryHistoryDto> = emptyList()
)
