package com.sentinel.host.data.session

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.sentinel.host.domain.session.ActiveCapturePermit
import com.sentinel.host.domain.session.CaptureOperation
import com.sentinel.host.domain.session.MonotonicClock
import com.sentinel.host.domain.session.SessionManager
import java.util.UUID

/**
 * JWT, device enrollment, and session-scoped capture authorization implementation.
 * Guarantees monotonic bounded deadlines, connection generation gating,
 * and operation-specific permit isolation.
 */
class SessionManagerImpl(
    context: Context,
    private val customPrefs: SharedPreferences? = null,
    private val clock: MonotonicClock = MonotonicClock {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
) : SessionManager {

    companion object {
        private const val PREFS_NAME = "sentinel_session"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_ENROLLMENT_APPROVED = "enrollment_approved"

        const val MIN_DURATION_SECONDS = 1L
        const val MAX_DURATION_SECONDS = 300L
        const val DEFAULT_DURATION_SECONDS = 300L
        val NIL_UUID = UUID(0L, 0L)
    }

    private val lock = Any()
    private var connectionGeneration: Long = 1L
    private val activePermits = mutableMapOf<UUID, ActiveCapturePermit>()

    private val prefs: SharedPreferences by lazy {
        customPrefs ?: run {
            val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }

            EncryptedSharedPreferences.create(
                PREFS_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                deviceContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    override fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun getToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }

    override fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    override fun saveServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    override fun getServerUrl(): String? {
        return prefs.getString(KEY_SERVER_URL, null)
    }

    override fun saveFcmToken(token: String) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    override fun getFcmToken(): String? {
        return prefs.getString(KEY_FCM_TOKEN, null)
    }

    // Enrollment Lifecycle (Device onboarding)
    override fun setEnrollmentApproved(approved: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENROLLMENT_APPROVED, approved)
            .apply()
        if (!approved) {
            clearAllPermits()
        }
    }

    override fun isEnrollmentApproved(): Boolean {
        return prefs.getBoolean(KEY_ENROLLMENT_APPROVED, false)
    }

    // Connection Generation tracking
    override fun getConnectionGeneration(): Long = synchronized(lock) {
        connectionGeneration
    }

    override fun incrementConnectionGeneration(): Long = synchronized(lock) {
        ++connectionGeneration
    }

    // Capture Session Authorization
    override fun authorizeCapture(
        streamId: UUID,
        operation: CaptureOperation,
        durationSeconds: Long
    ): ActiveCapturePermit? = synchronized(lock) {
        if (!isEnrollmentApproved()) return null
        if (streamId == NIL_UUID) return null

        val now = clock.elapsedRealtime()

        // If this stream already has an active valid permit for this operation, preserve existing deadline
        val existingPermit = activePermits[streamId]
        if (existingPermit != null && !existingPermit.isExpired(now)) {
            if (existingPermit.operation == operation) {
                return existingPermit
            }
            return null
        }

        // If an active session for another stream with the same operation exists, reject
        val anyActiveSameOp = activePermits.values.firstOrNull { it.operation == operation && !it.isExpired(now) }
        if (anyActiveSameOp != null && anyActiveSameOp.streamId != streamId) {
            return null
        }

        // Validate duration before arithmetic
        val clampedDuration = durationSeconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
        val deadlineMonotonicMs = now + (clampedDuration * 1000L)

        val permit = ActiveCapturePermit(
            permitId = UUID.randomUUID().toString(),
            streamId = streamId,
            operation = operation,
            durationSeconds = clampedDuration,
            authorizedAtMonotonicMs = now,
            deadlineMonotonicMs = deadlineMonotonicMs,
            connectionGeneration = connectionGeneration
        )

        activePermits[streamId] = permit
        return permit
    }

    // Hardware Boundary Verification
    override fun isCapturePermitted(streamId: UUID, operation: CaptureOperation): Boolean = synchronized(lock) {
        if (!isEnrollmentApproved()) return false
        if (streamId == NIL_UUID) return false

        val permit = activePermits[streamId] ?: return false
        if (permit.operation != operation) return false

        val now = clock.elapsedRealtime()
        if (permit.isExpired(now)) {
            activePermits.remove(streamId)
            return false
        }

        if (permit.connectionGeneration != connectionGeneration) {
            activePermits.remove(streamId)
            return false
        }

        return true
    }

    override fun getActivePermit(streamId: UUID): ActiveCapturePermit? = synchronized(lock) {
        val permit = activePermits[streamId] ?: return null
        if (permit.isExpired(clock.elapsedRealtime())) {
            activePermits.remove(streamId)
            return null
        }
        return permit
    }

    override fun listActivePermits(): List<ActiveCapturePermit> = synchronized(lock) {
        val now = clock.elapsedRealtime()
        activePermits.entries.removeAll { it.value.isExpired(now) }
        return activePermits.values.toList()
    }

    override fun revokePermit(streamId: UUID): Boolean = synchronized(lock) {
        return activePermits.remove(streamId) != null
    }

    // Invalidation Hooks
    override fun invalidateOnStop(streamId: UUID?): Unit = synchronized(lock) {
        if (streamId != null) {
            activePermits.remove(streamId)
        } else {
            activePermits.clear()
        }
    }

    override fun invalidateOnDisconnect(): Unit = synchronized(lock) {
        connectionGeneration++
        activePermits.clear()
    }

    override fun clearAllPermits(): Unit = synchronized(lock) {
        activePermits.clear()
    }

    override fun hasSession(): Boolean {
        return getToken() != null && getServerUrl() != null
    }

    override fun clear() {
        prefs.edit().clear().apply()
        synchronized(lock) {
            connectionGeneration = 1L
            activePermits.clear()
        }
    }
}
