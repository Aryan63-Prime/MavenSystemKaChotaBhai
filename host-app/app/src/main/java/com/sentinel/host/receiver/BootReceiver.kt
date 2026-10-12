package com.sentinel.host.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sentinel.host.service.SentinelForegroundService
import com.sentinel.host.worker.SentinelWatchdogWorker

/**
 * Simple boot receiver. Fires ONLY after user unlock (BOOT_COMPLETED).
 * No directBootAware, no AlarmManager complexity — just start the FGS directly.
 * WorkManager watchdog is scheduled as a safety net.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Sentinel:Boot"
        var sessionManagerProvider: ((Context) -> com.sentinel.host.domain.session.SessionManager)? = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "BootReceiver fired: $action")

        val sessionManager = sessionManagerProvider?.invoke(context) ?: com.sentinel.host.data.session.SessionManagerImpl(context)
        if (!sessionManager.isEnrollmentApproved()) {
            Log.w(TAG, "BootReceiver: On-device host approval not granted, blocking service start")
            return
        }

        // Schedule WorkManager watchdog as safety net
        SentinelWatchdogWorker.schedule(context)
        // Schedule exact-while-idle rolling watchdog alarm
        SentinelWatchdogReceiver.scheduleNext(context)

        // Direct foreground service start
        try {
            Log.i(TAG, "Starting SentinelForegroundService from $action")
            val serviceIntent = Intent(context, SentinelForegroundService::class.java).apply {
                val isTest = action == "com.sentinel.host.TEST_COMMAND"
                putExtra(SentinelForegroundService.EXTRA_FROM_BOOT, !isTest)
                intent.getStringExtra("EXTRA_TEST_RAW_MSG")?.let {
                    putExtra("EXTRA_TEST_RAW_MSG", it)
                }
                intent.getStringExtra("EXTRA_TEST_RAW_MSG_B64")?.let {
                    putExtra("EXTRA_TEST_RAW_MSG_B64", it)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Service start command issued successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}", e)
            // Fallback: use WorkManager immediate job
            try {
                SentinelWatchdogWorker.runImmediately(context)
                Log.i(TAG, "WorkManager immediate job enqueued as fallback")
            } catch (we: Exception) {
                Log.e(TAG, "WorkManager fallback also failed: ${we.message}", we)
            }
        }
    }
}
