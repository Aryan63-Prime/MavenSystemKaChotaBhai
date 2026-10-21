package com.sentinel.host.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import com.sentinel.host.data.device.MdmManager
import com.sentinel.host.service.SentinelForegroundService

/**
 * Out-of-Band Offline SMS Command & Wake Receiver ("Zero-Internet Blackout Mode").
 * Allows waking the host service, forcing an uplink reconnect, or locking the device
 * via SMS (#SENTINEL:WAKE, #SENTINEL:PING, #SENTINEL:LOCK) when data connectivity is lost.
 */
class SentinelSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Sentinel:SmsRx"
        const val PREFIX_WAKE = "#SENTINEL:WAKE"
        const val PREFIX_PING = "#SENTINEL:PING"
        const val PREFIX_LOCK = "#SENTINEL:LOCK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming SMS intent: ${e.message}")
            null
        } ?: return

        for (sms in messages) {
            val body = sms?.messageBody?.trim()?.uppercase() ?: continue
            if (body.startsWith(PREFIX_WAKE) || body.startsWith(PREFIX_PING) || body.startsWith(PREFIX_LOCK)) {
                Log.i(TAG, "Received tactical SMS command: $body")

                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Sentinel:SmsCommandWakeLock"
                )
                wakeLock?.acquire(15_000L)

                try {
                    if (body.startsWith(PREFIX_LOCK)) {
                        val (locked, method) = MdmManager(context.applicationContext).lockDeviceNow()
                        Log.i(TAG, "Executed offline SMS lock command (locked=$locked, method=$method)")
                    }

                    val serviceIntent = Intent(context, SentinelForegroundService::class.java).apply {
                        action = SentinelForegroundService.ACTION_WAKE
                        putExtra("EXTRA_FROM_FCM_WAKE", true)
                        putExtra("EXTRA_FROM_SMS_WAKE", true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling SMS command: ${e.message}", e)
                }
                break
            }
        }
    }
}
