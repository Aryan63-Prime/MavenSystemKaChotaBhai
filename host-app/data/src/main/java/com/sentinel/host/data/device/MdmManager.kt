package com.sentinel.host.data.device

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MdmManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Sentinel:MDM"
        private const val ADMIN_RECEIVER_CLASS = "com.sentinel.host.receiver.SentinelDeviceAdminReceiver"
    }

    private val dpm by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    val adminComponent by lazy {
        ComponentName(context.packageName, ADMIN_RECEIVER_CLASS)
    }

    val isDeviceOwner: Boolean
        get() = try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }

    val isProfileOwner: Boolean
        get() = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.isProfileOwnerApp(context.packageName)
            } else false
        } catch (e: Exception) {
            false
        }

    val isDeviceAdminActive: Boolean
        get() = try {
            dpm.isAdminActive(adminComponent)
        } catch (e: Exception) {
            false
        }

    fun getMdmStatus(): Map<String, Any> {
        return mapOf(
            "isDeviceAdminActive" to isDeviceAdminActive,
            "isDeviceOwner" to isDeviceOwner,
            "isProfileOwner" to isProfileOwner
        )
    }

    /**
     * Remotely locks the device screen immediately.
     * Uses DevicePolicyManager.lockNow() if Device Admin is active,
     * or falls back to AccessibilityService GLOBAL_ACTION_LOCK_SCREEN on Android 9+.
     */
    fun lockDeviceNow(): Pair<Boolean, String> {
        var locked = false
        var method = ""

        if (isDeviceAdminActive) {
            try {
                dpm.lockNow()
                Log.i(TAG, "Device locked via DevicePolicyManager.lockNow()")
                locked = true
                method = "DevicePolicyManager"
            } catch (e: Exception) {
                Log.w(TAG, "dpm.lockNow() failed: ${e.message}")
            }
        }

        // Also trigger Accessibility GLOBAL_ACTION_LOCK_SCREEN to ensure keyguard engages
        // even if developer options 'Stay awake while charging' is active on test device
        val (accessLocked, accessMethod) = fallbackAccessibilityLock()
        if (accessLocked) {
            locked = true
            method = if (method.isNotEmpty()) "$method + $accessMethod" else accessMethod
        }

        return if (locked) Pair(true, method) else Pair(false, "Neither Device Admin nor Accessibility Service active to lock device")
    }

    private fun fallbackAccessibilityLock(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val accessClass = Class.forName("com.sentinel.host.service.SentinelAccessibilityService")
                val instanceField = accessClass.getDeclaredField("instance")
                instanceField.isAccessible = true
                val accessService = instanceField.get(null) as? AccessibilityService
                if (accessService != null) {
                    val locked = accessService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    Log.i(TAG, "Device locked via AccessibilityService GLOBAL_ACTION_LOCK_SCREEN: $locked")
                    if (locked) return Pair(true, "AccessibilityService")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility lock fallback failed: ${e.message}")
            }
        }
        return Pair(false, "Neither Device Admin nor Accessibility Service active to lock device")
    }

    /**
     * Prevents the user from uninstalling the Sentinel host app.
     * Requires Device Owner mode.
     */
    fun setUninstallProtection(enabled: Boolean): Boolean {
        if (!isDeviceOwner) {
            Log.w(TAG, "Cannot block uninstall: Not a Device Owner")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.setUninstallBlocked(adminComponent, context.packageName, enabled)
                Log.i(TAG, "Uninstall blocked set to: $enabled")
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting uninstall protection: ${e.message}", e)
            false
        }
    }

    /**
     * Silently auto-grants all runtime permissions without showing prompts to the user.
     * Requires Device Owner or Profile Owner mode.
     */
    fun autoGrantAllPermissions(): Boolean {
        if (!isDeviceOwner && !isProfileOwner) {
            Log.w(TAG, "Cannot auto-grant permissions: Not a Device Owner or Profile Owner")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val permissionsToGrant = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            "android.permission.READ_LOGS",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN"
        )

        var allSuccess = true
        for (perm in permissionsToGrant) {
            try {
                dpm.setPermissionGrantState(
                    adminComponent,
                    context.packageName,
                    perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.i(TAG, "Auto-granted runtime permission: $perm")
            } catch (e: Exception) {
                Log.e(TAG, "Failed auto-granting $perm: ${e.message}")
                allSuccess = false
            }
        }
        return allSuccess
    }

    /**
     * Remotely triggers an enterprise factory reset / wipe in case of catastrophic theft.
     * Requires Device Owner mode.
     */
    fun remoteFactoryWipe(): Boolean {
        if (!isDeviceOwner) {
            Log.w(TAG, "Cannot remote wipe: Not a Device Owner")
            return false
        }
        return try {
            Log.w(TAG, "Executing remote factory wipe!")
            dpm.wipeData(0)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute wipeData: ${e.message}")
            false
        }
    }
}
