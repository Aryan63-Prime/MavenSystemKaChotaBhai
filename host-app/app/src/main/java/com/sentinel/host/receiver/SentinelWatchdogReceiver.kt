package com.sentinel.host.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.sentinel.host.service.SentinelForegroundService

/**
 * Rolling exact-while-idle watchdog receiver.
 * Punches through Android Doze mode and aggressive OEM power managers
 * (such as Vivo OriginOS / FuntouchOS on Snapdragon 8 Elite) where WorkManager
 * is batched and delayed for hours.
 *
 * Runs on a self-perpetuating 10-minute heartbeat cycle.
 */
class SentinelWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Sentinel:WatchdogRx"
        const val ACTION_WATCHDOG_TICK = "com.sentinel.host.action.WATCHDOG_TICK"
        private const val WATCHDOG_REQUEST_CODE = 4001
        private const val WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes
        var sessionManagerProvider: ((Context) -> com.sentinel.host.domain.session.SessionManager)? = null

        /**
         * Schedules or advances the next exact-while-idle alarm.
         */
        fun scheduleNext(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val sessionManager = sessionManagerProvider?.invoke(context) ?: com.sentinel.host.data.session.SessionManagerImpl(context)
            if (!sessionManager.isEnrollmentApproved()) {
                Log.w(TAG, "Watchdog alarm scheduling skipped: On-device host approval not granted")
                return
            }

            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, SentinelWatchdogReceiver::class.java).apply {
                    action = ACTION_WATCHDOG_TICK
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAt = SystemClock.elapsedRealtime() + delayMs

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                triggerAt,
                                pendingIntent
                            )
                        } else {
                            alarmManager.setAndAllowWhileIdle(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                triggerAt,
                                pendingIntent
                            )
                        }
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Scheduled rolling exact-while-idle alarm in ${delayMs / 1000}s")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule exact watchdog alarm: ${e.message}", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val sessionManager = sessionManagerProvider?.invoke(context) ?: com.sentinel.host.data.session.SessionManagerImpl(context)
        if (!sessionManager.isEnrollmentApproved()) {
            Log.w(TAG, "Watchdog alarm skipped: On-device host approval not granted")
            return
        }

        Log.i(TAG, "Watchdog alarm triggered (action=${intent?.action})")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Sentinel:WatchdogWakeLock"
        )
        wakeLock?.acquire(10_000L)

        try {
            // Revive / nudge foreground service
            val serviceIntent = Intent(context, SentinelForegroundService::class.java).apply {
                action = SentinelForegroundService.ACTION_WAKE
                putExtra("EXTRA_FROM_WATCHDOG", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Ensured SentinelForegroundService is running via watchdog alarm")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service from watchdog: ${e.message}", e)
        } finally {
            // Schedule the next heartbeat in the rolling chain
            scheduleNext(context)
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {}
        }
    }
}
