package com.sentinel.admin.data.remote.api

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit API interface for device endpoints.
 *
 * Uses the existing backend REST API.
 * Authentication via JWT Bearer token in Authorization header.
 */
interface DeviceApi {

    /**
     * GET /devices — Returns all connected devices with realtime state.
     */
    @GET("/devices")
    suspend fun getDevices(
        @Header("Authorization") authorization: String
    ): DevicesResponse

    /**
     * GET /devices/{deviceId} — Returns a single device by ID.
     */
    @GET("/devices/{deviceId}")
    suspend fun getDevice(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String
    ): DeviceDto

    /**
     * POST /devices/{deviceId}/wake — Sends a high-priority FCM wakeup ping.
     */
    @POST("/devices/{deviceId}/wake")
    suspend fun wakeDevice(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String
    ): WakeResponseDto

    /**
     * GET /devices/{deviceId}/route — Returns historical breadcrumb route points.
     */
    @GET("/devices/{deviceId}/route")
    suspend fun getRouteHistory(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String,
        @Query("start") start: String? = null,
        @Query("end") end: String? = null
    ): RouteHistoryResponse

    /**
     * GET /devices/{deviceId}/battery-history — Returns historical battery and thermal telemetry.
     */
    @GET("/devices/{deviceId}/battery-history")
    suspend fun getBatteryHistory(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String
    ): BatteryHistoryResponse
}
