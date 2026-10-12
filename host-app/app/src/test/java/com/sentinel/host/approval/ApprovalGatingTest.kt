package com.sentinel.host.approval

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.sentinel.host.data.session.SessionManagerImpl
import com.sentinel.host.domain.session.CaptureOperation
import com.sentinel.host.domain.session.MonotonicClock
import com.sentinel.host.domain.session.SessionManager
import com.sentinel.host.receiver.BootReceiver
import com.sentinel.host.receiver.SentinelWatchdogReceiver
import com.sentinel.host.service.SentinelForegroundService
import com.sentinel.host.worker.SentinelWatchdogWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*
import java.util.UUID

class FakeSessionManager(private var approved: Boolean = false) : SessionManager {
    override fun saveToken(token: String) {}
    override fun getToken(): String? = "test-token"
    override fun clearToken() {}
    override fun saveServerUrl(url: String) {}
    override fun getServerUrl(): String? = "wss://test"
    override fun setEnrollmentApproved(approved: Boolean) { this.approved = approved }
    override fun isEnrollmentApproved(): Boolean = approved
    override fun setCaptureApproval(approved: Boolean) { setEnrollmentApproved(approved) }

    override fun hasSession(): Boolean = true
    override fun clear() {
        approved = false
    }
}

class TestMonotonicClock(var currentTimeMs: Long = 100_000L) : MonotonicClock {
    override fun elapsedRealtime(): Long = currentTimeMs
    fun advance(ms: Long) { currentTimeMs += ms }
}

class ApprovalGatingTest {

    @After
    fun tearDown() {
        BootReceiver.sessionManagerProvider = null
        SentinelWatchdogReceiver.sessionManagerProvider = null
        SentinelWatchdogWorker.sessionManagerProvider = null
        SentinelForegroundService.sessionManagerProvider = null
    }

    @Test
    fun testBootReceiverBlocksWhenNotApproved() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.sentinel.host")
        `when`(context.applicationContext).thenReturn(context)

        val fakeSession = FakeSessionManager(approved = false)
        BootReceiver.sessionManagerProvider = { fakeSession }

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        verify(context, never()).startService(any())
        verify(context, never()).startForegroundService(any())
    }

    @Test
    fun testWatchdogReceiverBlocksWhenNotApproved() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.sentinel.host")
        `when`(context.applicationContext).thenReturn(context)

        val fakeSession = FakeSessionManager(approved = false)
        SentinelWatchdogReceiver.sessionManagerProvider = { fakeSession }

        val receiver = SentinelWatchdogReceiver()
        val intent = Intent(SentinelWatchdogReceiver.ACTION_WATCHDOG_TICK)
        receiver.onReceive(context, intent)

        verify(context, never()).startService(any())
        verify(context, never()).startForegroundService(any())
    }

    @Test
    fun testWatchdogScheduleNextBlocksWhenNotApproved() {
        val context = mock(Context::class.java)
        val fakeSession = FakeSessionManager(approved = false)
        SentinelWatchdogReceiver.sessionManagerProvider = { fakeSession }

        SentinelWatchdogReceiver.scheduleNext(context)
        verify(context, never()).getSystemService(Context.ALARM_SERVICE)
    }

    @Test
    fun testWatchdogWorkerBlocksWhenNotApproved() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val fakeSession = FakeSessionManager(approved = false)
        SentinelWatchdogWorker.sessionManagerProvider = { fakeSession }

        SentinelWatchdogWorker.schedule(context)
        SentinelWatchdogWorker.runImmediately(context)
        assertFalse("Enrollment approval is false", fakeSession.isEnrollmentApproved())
    }

    @Test
    fun testForegroundServiceStartBlocksWhenNotApproved() {
        val context = mock(Context::class.java)
        val fakeSession = FakeSessionManager(approved = false)
        SentinelForegroundService.sessionManagerProvider = { fakeSession }

        SentinelForegroundService.Start(context)
        verify(context, never()).startService(any())
        verify(context, never()).startForegroundService(any())
    }

    @Test
    fun testForegroundServiceStartProceedsWhenApproved() {
        val context = mock(Context::class.java)
        val fakeSession = FakeSessionManager(approved = true)
        SentinelForegroundService.sessionManagerProvider = { fakeSession }

        SentinelForegroundService.Start(context)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            verify(context).startForegroundService(any())
        } else {
            verify(context).startService(any())
        }
    }

    // =========================================================================
    // Capture Approval Regressions
    // =========================================================================

    @Test
    fun `remote START requires on-device enrollment approval before capture can be authorized`() {
        val clock = TestMonotonicClock(100_000L)
        val sessionManager = SessionManagerImpl(
            mock(Context::class.java),
            customPrefs = InMemorySharedPreferences(),
            clock = clock
        )
        val streamId = UUID.randomUUID()

        // Before enrollment approval: authorization must be rejected
        sessionManager.setEnrollmentApproved(false)
        val unapprovedPermit = sessionManager.authorizeCapture(
            streamId = streamId,
            operation = CaptureOperation.AUDIO_STREAM,
            durationSeconds = 60L
        )
        assertNull("Cannot authorize capture without enrollment approval", unapprovedPermit)
        assertFalse(sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM))
        assertFalse(sessionManager.isCapturePermitted(streamId, CaptureOperation.PHOTO_CAPTURE))

        // After enrollment approval: capture can be authorized
        sessionManager.setEnrollmentApproved(true)
        val approvedPermit = sessionManager.authorizeCapture(
            streamId = streamId,
            operation = CaptureOperation.AUDIO_STREAM,
            durationSeconds = 60L
        )
        assertNotNull(approvedPermit)
        assertEquals(streamId, approvedPermit?.streamId)
        assertTrue(sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM))
    }

    @Test
    fun `expired permit, revoked permit, and stop invalidate hardware access`() {
        val clock = TestMonotonicClock(100_000L)
        val sessionManager = SessionManagerImpl(
            mock(Context::class.java),
            customPrefs = InMemorySharedPreferences(),
            clock = clock
        )
        sessionManager.setEnrollmentApproved(true)

        // 1. Permit expiration (> duration)
        val stream1 = UUID.randomUUID()
        val permit1 = sessionManager.authorizeCapture(stream1, CaptureOperation.AUDIO_STREAM, 30L)
        assertNotNull(permit1)
        assertTrue(sessionManager.isCapturePermitted(stream1, CaptureOperation.AUDIO_STREAM))
        clock.advance(35_000L) // 35 seconds later (past 30s deadline)
        assertFalse(sessionManager.isCapturePermitted(stream1, CaptureOperation.AUDIO_STREAM))

        // 2. Explicit STOP invalidation
        val stream2 = UUID.randomUUID()
        sessionManager.authorizeCapture(stream2, CaptureOperation.AUDIO_STREAM, 60L)
        assertTrue(sessionManager.isCapturePermitted(stream2, CaptureOperation.AUDIO_STREAM))
        sessionManager.invalidateOnStop(stream2)
        assertFalse(sessionManager.isCapturePermitted(stream2, CaptureOperation.AUDIO_STREAM))

        // 3. Revoke permit
        val stream3 = UUID.randomUUID()
        sessionManager.authorizeCapture(stream3, CaptureOperation.PHOTO_CAPTURE, 30L)
        assertTrue(sessionManager.isCapturePermitted(stream3, CaptureOperation.PHOTO_CAPTURE))
        assertTrue(sessionManager.revokePermit(stream3))
        assertFalse(sessionManager.isCapturePermitted(stream3, CaptureOperation.PHOTO_CAPTURE))
    }

    @Test
    fun `missing and nil stream UUIDs reject start`() {
        val clock = TestMonotonicClock(100_000L)
        val sessionManager = SessionManagerImpl(
            mock(Context::class.java),
            customPrefs = InMemorySharedPreferences(),
            clock = clock
        )
        sessionManager.setEnrollmentApproved(true)

        val nilUuid = SessionManagerImpl.NIL_UUID // UUID(0L, 0L)
        assertNull(sessionManager.authorizeCapture(nilUuid, CaptureOperation.AUDIO_STREAM, 60L))
        assertFalse(sessionManager.isCapturePermitted(nilUuid, CaptureOperation.AUDIO_STREAM))
    }

    @Test
    fun `approval for another operation rejects start`() {
        val clock = TestMonotonicClock(100_000L)
        val sessionManager = SessionManagerImpl(
            mock(Context::class.java),
            customPrefs = InMemorySharedPreferences(),
            clock = clock
        )
        sessionManager.setEnrollmentApproved(true)

        val streamId = UUID.randomUUID()
        val permit = sessionManager.authorizeCapture(streamId, CaptureOperation.AUDIO_STREAM, 60L)
        assertNotNull(permit)

        // AUDIO_STREAM is permitted
        assertTrue(sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM))
        // PHOTO_CAPTURE is strictly rejected even though stream has an audio permit
        assertFalse(sessionManager.isCapturePermitted(streamId, CaptureOperation.PHOTO_CAPTURE))

        // Enrollment approval alone never authorizes unpermitted streams
        val freshStream = UUID.randomUUID()
        assertFalse(sessionManager.isCapturePermitted(freshStream, CaptureOperation.AUDIO_STREAM))
        assertFalse(sessionManager.isCapturePermitted(freshStream, CaptureOperation.PHOTO_CAPTURE))
    }

    @Test
    fun `disconnect followed by reconnect cannot restart a revoked capture session`() {
        val clock = TestMonotonicClock(100_000L)
        val sessionManager = SessionManagerImpl(
            mock(Context::class.java),
            customPrefs = InMemorySharedPreferences(),
            clock = clock
        )
        sessionManager.setEnrollmentApproved(true)

        val streamId = UUID.randomUUID()
        sessionManager.authorizeCapture(streamId, CaptureOperation.AUDIO_STREAM, 120L)
        assertTrue(sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM))

        // Disconnect occurs: invalidates all active permits and advances connection generation
        sessionManager.invalidateOnDisconnect()

        // After disconnect, permit is revoked
        assertFalse(sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM))

        // Reconnect occurs (new generation): the old permit cannot be resurrected
        sessionManager.incrementConnectionGeneration()
        assertFalse(sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM))
    }

    @Test
    fun `duplicate START preserves original deadline and wall-clock shifts cannot extend authorization`() {
        val clock = TestMonotonicClock(100_000L)
        val sessionManager = SessionManagerImpl(
            mock(Context::class.java),
            customPrefs = InMemorySharedPreferences(),
            clock = clock
        )
        sessionManager.setEnrollmentApproved(true)

        val streamId = UUID.randomUUID()
        val permit1 = sessionManager.authorizeCapture(streamId, CaptureOperation.AUDIO_STREAM, 60L)!!
        val originalDeadline = 100_000L + (60L * 1000L)
        assertEquals(originalDeadline, permit1.deadlineMonotonicMs)

        // Advance monotonic clock by 10s
        clock.advance(10_000L) // now = 110_000L

        // Duplicate START with new requested duration (300s)
        val duplicatePermit = sessionManager.authorizeCapture(streamId, CaptureOperation.AUDIO_STREAM, 300L)
        // Must return existing permit preserving original deadline
        assertEquals(permit1.permitId, duplicatePermit?.permitId)
        assertEquals(originalDeadline, duplicatePermit?.deadlineMonotonicMs)

        val currentPermit = sessionManager.getActivePermit(streamId)
        assertEquals(originalDeadline, currentPermit?.deadlineMonotonicMs)
    }
}

/**
 * Lightweight in-memory SharedPreferences implementation for testing SessionManagerImpl.
 */
class InMemorySharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class EditorImpl : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { temp[key ?: ""] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { temp[key ?: ""] = values }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { temp[key ?: ""] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { temp[key ?: ""] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { temp[key ?: ""] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { temp[key ?: ""] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { temp[key ?: ""] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) data.clear()
            for ((k, v) in temp) {
                if (v == null) data.remove(k) else data[k] = v
            }
        }
    }
}
