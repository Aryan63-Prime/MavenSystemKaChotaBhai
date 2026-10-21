package com.sentinel.host

import android.app.Application
import com.sentinel.host.data.device.CrashReporter
import com.sentinel.host.service.SentinelAccessibilityService
import com.sentinel.host.service.SentinelForegroundService
import com.sentinel.host.worker.SentinelWatchdogWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SentinelApp : Application() {

    @Inject lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        crashReporter.initializeGlobalHandler()
        SentinelWatchdogWorker.schedule(this)
        SentinelAccessibilityService.foregroundStarter = { ctx ->
            try {
                SentinelForegroundService.Start(ctx)
            } catch (_: Exception) {}
        }
    }
}
