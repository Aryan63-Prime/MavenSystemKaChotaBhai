package com.sentinel.host.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sentinel.host.service.SentinelForegroundService
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that acts as a 15-minute persistent watchdog.
 * Re-enforces [SentinelForegroundService] if it was killed by Doze mode or low memory.
 */
class SentinelWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "Sentinel:Watchdog"
        private const val WORK_NAME = "SentinelKeepAliveWatchdog"
        private const val ONE_TIME_WORK_NAME = "SentinelBootWatchdog"
        var sessionManagerProvider: ((Context) -> com.sentinel.host.domain.session.SessionManager)? = null

        /**
         * Enqueues an immediate one-time WorkManager job (backed by system JobScheduler)
         * to start SentinelForegroundService immediately on boot without FGS restrictions.
         */
        fun runImmediately(context: Context) {
            val sessionManager = sessionManagerProvider?.invoke(context) ?: com.sentinel.host.data.session.SessionManagerImpl(context)
            if (!sessionManager.isEnrollmentApproved()) {
                Log.w(TAG, "Immediate boot watchdog skipped: On-device host approval not granted")
                return
            }

            try {
                val workRequest = OneTimeWorkRequestBuilder<SentinelWatchdogWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONE_TIME_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
                Log.i(TAG, "Immediate boot watchdog enqueued successfully via JobScheduler")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue immediate boot watchdog: ${e.message}", e)
            }
        }

        /**
         * Schedules a 15-minute periodic watchdog job.
         */
        fun schedule(context: Context) {
            val sessionManager = sessionManagerProvider?.invoke(context) ?: com.sentinel.host.data.session.SessionManagerImpl(context)
            if (!sessionManager.isEnrollmentApproved()) {
                Log.w(TAG, "Periodic watchdog scheduling skipped: On-device host approval not granted")
                return
            }

            try {
                val workRequest = PeriodicWorkRequestBuilder<SentinelWatchdogWorker>(
                    15, TimeUnit.MINUTES,
                    5, TimeUnit.MINUTES // Flex interval
                ).build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                Log.i(TAG, "Sentinel periodic watchdog scheduled successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule periodic watchdog: ${e.message}", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        val sessionManager = sessionManagerProvider?.invoke(applicationContext) ?: com.sentinel.host.data.session.SessionManagerImpl(applicationContext)
        if (!sessionManager.isEnrollmentApproved()) {
            Log.w(TAG, "Watchdog execution skipped: On-device host approval not granted")
            return Result.success()
        }

        Log.i(TAG, "Watchdog execution triggered — checking service health")
        try {
            SentinelForegroundService.Start(applicationContext)
            Log.i(TAG, "Watchdog successfully ensured SentinelForegroundService is running")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog failed to restart service: ${e.message}", e)
            return Result.retry()
        }
    }
}
