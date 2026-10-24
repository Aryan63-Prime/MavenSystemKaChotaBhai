package com.sentinel.admin.data.remote.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
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

    @GET("/admin/iam/list")
    suspend fun listAdmins(@Header("Authorization") bearerToken: String): AdminIamListResponseDto

    @POST("/admin/iam/issue")
    suspend fun issueTempAdmin(
        @Header("Authorization") bearerToken: String,
        @Body request: IssueAdminRequestDto
    ): IssueAdminResponseDto

    @POST("/admin/iam/status")
    suspend fun setAdminStatus(
        @Header("Authorization") bearerToken: String,
        @Body request: TargetAdminStatusRequestDto
    ): AdminActionResponseDto

    @POST("/admin/iam/permissions")
    suspend fun setAdminPermissions(
        @Header("Authorization") bearerToken: String,
        @Body request: TargetAdminStatusRequestDto
    ): AdminActionResponseDto

    @POST("/admin/iam/panic")
    suspend fun triggerPanicLockdown(
        @Header("Authorization") bearerToken: String
    ): AdminActionResponseDto

    @GET("/admin/iam/audit")
    suspend fun getAdminAuditEvents(@Header("Authorization") bearerToken: String): AdminIamAuditResponseDto
}
