package com.sentinel.host.data.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SystemInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hardwareInfoProvider: HardwareInfoProvider,
    private val rootDetectionManager: RootDetectionManager,
    private val securitySettingsMonitor: SecuritySettingsMonitor
) {
    fun getSystemInfo(): Map<String, Any> {
        val hw = hardwareInfoProvider.getHardwareInfo()

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val stat = StatFs(Environment.getDataDirectory().path)
        val availableStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
        val totalStorageGb = (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 0

        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val batteryTempC = tempTenths / 10.0

        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

        // Instantaneous battery current & power
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentNowUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val voltageMv = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val powerWatts = if (voltageMv > 0 && currentNowUa != 0) {
            (voltageMv / 1000.0) * (abs(currentNowUa) / 1_000_000.0)
        } else {
            0.0
        }

        // Wi-Fi and Gateway info
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = wifiManager?.connectionInfo
        val ssid = try {
            val s = wifiInfo?.ssid?.replace("\"", "") ?: "Unknown"
            if (s == "<unknown ssid>") "Connected" else s
        } catch (_: Exception) {
            "Connected"
        }

        val wifiBssid = try {
            val b = wifiInfo?.bssid ?: "N/A"
            if (b == "02:00:00:00:00:00" || b == "<unknown bssid>") "N/A" else b
        } catch (_: Exception) {
            "N/A"
        }

        val wifiFrequency = wifiInfo?.frequency ?: 0
        val wifiBand = when {
            wifiFrequency > 5925 -> "6 GHz (Wi-Fi 6E/7)"
            wifiFrequency > 4900 -> "5 GHz"
            wifiFrequency > 2400 -> "2.4 GHz"
            else -> "Standard"
        }
        val wifiLinkSpeed = if ((wifiInfo?.linkSpeed ?: -1) > 0) "${wifiInfo?.linkSpeed} Mbps" else "N/A"
        val ipInt = wifiInfo?.ipAddress ?: 0
        val localIp = if (ipInt != 0) {
            String.format(
                java.util.Locale.US,
                "%d.%d.%d.%d",
                ipInt and 0xff,
                (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff,
                (ipInt shr 24) and 0xff
            )
        } else "N/A"

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val linkProps = cm?.getLinkProperties(activeNet)
        val gatewayIp = linkProps?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "N/A"
        val dnsServers = linkProps?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(", ")?.ifBlank { "N/A" } ?: "N/A"
        val networkInterface = linkProps?.interfaceName ?: "wlan0"

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val carrier = telephonyManager?.networkOperatorName?.ifBlank { null }
            ?: telephonyManager?.simOperatorName?.ifBlank { null }
            ?: "Cellular"

        val batteryHealth = when (batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            else -> "Normal"
        }
        val batteryTech = batteryStatus?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-poly"

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (powerManager?.currentThermalStatus) {
                android.os.PowerManager.THERMAL_STATUS_NONE -> "Nominal"
                android.os.PowerManager.THERMAL_STATUS_LIGHT -> "Light Warm"
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttling"
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttling"
                android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Thermal"
                android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency Shutdown"
                else -> "Nominal"
            }
        } else "Nominal"

        val cpuCores = Runtime.getRuntime().availableProcessors()
        val kernelVersion = try {
            val ver = File("/proc/version").readText().trim()
            ver.split(" ").getOrNull(2) ?: System.getProperty("os.version") ?: "Linux"
        } catch (_: Exception) {
            System.getProperty("os.version") ?: "Linux"
        }

        // POSIX Uname
        val uts = try {
            android.system.Os.uname()
        } catch (_: Throwable) {
            null
        }
        val unameSysname = uts?.sysname ?: "Linux"
        val unameRelease = uts?.release ?: kernelVersion
        val unameMachine = uts?.machine ?: Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64"
        val unameVersion = uts?.version ?: ""

        // SELinux enforcement
        val isSelinuxEnforced = try {
            val clazz = Class.forName("android.os.SELinux")
            clazz.getMethod("isSELinuxEnforced").invoke(null) as Boolean
        } catch (_: Throwable) {
            true
        }

        // Root detection & Security posture
        val rootResult = rootDetectionManager.checkRoot()
        val secSnapshot = securitySettingsMonitor.getSnapshot()

        val uptimeMillis = android.os.SystemClock.elapsedRealtime()
        val uptimeHours = uptimeMillis / (1000 * 60 * 60)
        val uptimeMins = (uptimeMillis / (1000 * 60)) % 60
        val uptimeStr = "${uptimeHours}h ${uptimeMins}m"

        val soc = hw.socModel.ifBlank { hw.socManufacturer }.ifBlank { hw.hardware }

        return mapOf(
            "ramAvailableMb" to (memoryInfo.availMem / (1024 * 1024)),
            "ramTotalMb" to (memoryInfo.totalMem / (1024 * 1024)),
            "storageAvailableGb" to String.format("%.1f GB", availableStorageGb),
            "storageTotalGb" to String.format("%.1f GB", totalStorageGb),
            "batteryPercent" to batteryPct,
            "batteryTempC" to batteryTempC,
            "batteryVoltageMv" to voltageMv,
            "batteryHealth" to batteryHealth,
            "batteryTech" to batteryTech,
            "batteryCurrentUa" to currentNowUa,
            "batteryPowerWatts" to String.format(java.util.Locale.US, "%.2f W", powerWatts),
            "isCharging" to isCharging,
            "wifiSsid" to ssid,
            "wifiBssid" to wifiBssid,
            "wifiBand" to wifiBand,
            "wifiLinkSpeed" to wifiLinkSpeed,
            "localIp" to localIp,
            "gatewayIp" to gatewayIp,
            "dnsServers" to dnsServers,
            "networkInterface" to networkInterface,
            "carrier" to carrier,
            "thermalStatus" to thermalStatus,
            "cpuCores" to cpuCores,
            "kernelVersion" to kernelVersion,
            "unameSysname" to unameSysname,
            "unameRelease" to unameRelease,
            "unameMachine" to unameMachine,
            "unameVersion" to unameVersion,
            "isSelinuxEnforced" to isSelinuxEnforced,
            "isRooted" to rootResult.isRooted,
            "rootScore" to rootResult.rootScore,
            "rootVectors" to rootResult.detectedVectors,
            "isAdbEnabled" to secSnapshot.isAdbEnabled,
            "isDeveloperOptionsEnabled" to secSnapshot.isDeveloperOptionsEnabled,
            "isUsbConnected" to secSnapshot.isUsbConnected,
            "isUsbConfigured" to secSnapshot.isUsbConfigured,
            "isUsbAdbActive" to secSnapshot.isUsbAdbActive,
            "isWirelessAdbEnabled" to secSnapshot.isWirelessAdbEnabled,
            "isMockLocationEnabled" to secSnapshot.isMockLocationEnabled,
            "bootloader" to Build.BOOTLOADER,
            "radioVersion" to (Build.getRadioVersion() ?: "N/A"),
            "systemUptime" to uptimeStr,
            "cpuUsage" to "${getSampleCpuUsage()}%",
            "hardwareId" to hw.hardwareId,
            "hardwareSerial" to hw.hardwareSerial,
            "hardwareBrand" to hw.brand,
            "hardwareManufacturer" to hw.manufacturer,
            "hardwareModel" to hw.model,
            "hardwareDevice" to hw.device,
            "hardwareProduct" to hw.product,
            "hardwareBoard" to hw.board,
            "hardwareHardware" to hw.hardware,
            "hardwareSoc" to soc,
            "supportedAbis" to hw.supportedAbis,
            "osRelease" to hw.androidVersion,
            "osSdk" to hw.sdkInt,
            "securityPatch" to hw.securityPatch,
            "screenResolution" to hw.screenResolution,
            "screenDpi" to hw.screenDpi,
            "hardwareCallsign" to hw.hardwareCallsign
        )
    }

    private fun getSampleCpuUsage(): Int {
        return try {
            val statFile = File("/proc/stat")
            if (statFile.exists()) {
                val lines = statFile.readLines()
                if (lines.isNotEmpty()) {
                    val toks = lines[0].split("\\s+".toRegex())
                    val idle = toks[4].toLong()
                    val total = toks.drop(1).take(7).map { it.toLong() }.sum()
                    val usage = 100 - (idle * 100 / total)
                    return usage.toInt().coerceIn(1, 99)
                }
            }
            15
        } catch (e: Exception) {
            12
        }
    }
}
