package com.sentinel.host.data.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SecuritySettingsSnapshot(
    val isAdbEnabled: Boolean,
    val isDeveloperOptionsEnabled: Boolean,
    val isUsbConnected: Boolean,
    val isUsbConfigured: Boolean,
    val isUsbAdbActive: Boolean,
    val isWirelessAdbEnabled: Boolean,
    val isMockLocationEnabled: Boolean
)

/**
 * Monitors low-level developer options, ADB debugging, USB peripheral state,
 * and location spoofing settings.
 */
@Singleton
class SecuritySettingsMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getSnapshot(): SecuritySettingsSnapshot {
        val resolver = context.contentResolver

        val isAdbEnabled = try {
            Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Throwable) {
            false
        }

        val isDevOptionsEnabled = try {
            Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Throwable) {
            false
        }

        val usbIntent: Intent? = try {
            val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(null, filter)
            }
        } catch (_: Throwable) {
            null
        }

        val isUsbConnected = usbIntent?.getBooleanExtra("connected", false) ?: false
        val isUsbConfigured = usbIntent?.getBooleanExtra("configured", false) ?: false
        val isUsbAdbActive = isUsbConnected && (usbIntent?.getBooleanExtra("adb", false) ?: false)

        val isWirelessAdbEnabled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }

        val isMockLocationEnabled = try {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(resolver, Settings.Secure.ALLOW_MOCK_LOCATION, 0) != 0
        } catch (_: Throwable) {
            false
        }

        return SecuritySettingsSnapshot(
            isAdbEnabled = isAdbEnabled,
            isDeveloperOptionsEnabled = isDevOptionsEnabled,
            isUsbConnected = isUsbConnected,
            isUsbConfigured = isUsbConfigured,
            isUsbAdbActive = isUsbAdbActive,
            isWirelessAdbEnabled = isWirelessAdbEnabled,
            isMockLocationEnabled = isMockLocationEnabled
        )
    }
}
