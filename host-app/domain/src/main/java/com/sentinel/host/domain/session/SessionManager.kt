package com.sentinel.host.domain.session

import java.util.UUID

enum class CaptureOperation {
    AUDIO_STREAM,
    PHOTO_CAPTURE
}

data class ActiveCapturePermit(
    val permitId: String,
    val streamId: UUID,
    val operation: CaptureOperation,
    val durationSeconds: Long,
    val authorizedAtMonotonicMs: Long,
    val deadlineMonotonicMs: Long,
    val connectionGeneration: Long
) {
    fun isExpired(nowMonotonicMs: Long): Boolean = nowMonotonicMs > deadlineMonotonicMs
}

fun interface MonotonicClock {
    fun elapsedRealtime(): Long
}

/**
 * Single source of truth for credentials, device enrollment authorization,
 * and request-scoped capture approval.
 */
interface SessionManager {
    fun saveToken(token: String)
    fun getToken(): String?
    fun clearToken()
    fun saveServerUrl(url: String)
    fun getServerUrl(): String?
    fun saveFcmToken(token: String) {}
    fun getFcmToken(): String? = null

    // Enrollment lifecycle (onboarding approval)
    fun setEnrollmentApproved(approved: Boolean)
    fun isEnrollmentApproved(): Boolean
    fun setCaptureApproval(approved: Boolean) { setEnrollmentApproved(approved) }

    // Connection generation tracking
    fun getConnectionGeneration(): Long = 0L
    fun incrementConnectionGeneration(): Long = 0L

    // Capture session authorization (requires enrollment, exact non-nil UUID, clamped duration, generation check)
    fun authorizeCapture(
        streamId: UUID,
        operation: CaptureOperation,
        durationSeconds: Long
    ): ActiveCapturePermit? = null

    // Hardware-start boundary authorization
    fun isCapturePermitted(streamId: UUID, operation: CaptureOperation): Boolean = false
    fun getActivePermit(streamId: UUID): ActiveCapturePermit? = null
    fun listActivePermits(): List<ActiveCapturePermit> = emptyList()
    fun revokePermit(streamId: UUID): Boolean = false

    // Invalidation hooks
    fun invalidateOnStop(streamId: UUID?) {}
    fun invalidateOnDisconnect() {}
    fun clearAllPermits() {}

    fun hasSession(): Boolean
    fun clear()
}
