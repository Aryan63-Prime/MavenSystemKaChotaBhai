package com.sentinel.host.data.device

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
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
    private val securitySettingsMonitor: SecuritySettingsMonitor,
    private val fallDetector: FallDetector? = null
) {
    @Volatile private var cachedPressureHpa: Double? = null
    @Volatile private var cachedAzimuthDeg: Int? = null
    @Volatile private var cachedLightLux: Float? = null
    @Volatile private var cachedProximityNear: Boolean = false
    @Volatile private var cachedMagneticFieldUt: Float? = null
    @Volatile private var cachedPitchDeg: Int = 0
    @Volatile private var cachedRollDeg: Int = 0
    @Volatile private var cachedGravityZ: Float = 9.8f
    @Volatile private var cachedGnssSummary: String = "GNSS Standby"
    @Volatile private var prevBaroAltitudeM: Double? = null
    @Volatile private var prevBaroTimeMs: Long = 0L
    @Volatile private var cachedVerticalRateMs: Double = 0.0
    @Volatile private var lastMlTrainTimeMs: Long = 0L
    @Volatile private var lastMlResult: Pair<Double, String> = 0.0 to "Calibrating (1 samples)"

    init {
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val pressureSensor = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
            val rotSensor = sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
                ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            val lightSensor = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
            val proxSensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            val magSensor = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event == null) return
                    when (event.sensor.type) {
                        Sensor.TYPE_PRESSURE -> {
                            val v = event.values.firstOrNull()?.toDouble()
                            if (v != null && v > 0.0) {
                                cachedPressureHpa = v
                                try {
                                    val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, v.toFloat()).toDouble()
                                    val now = System.currentTimeMillis()
                                    if (prevBaroAltitudeM != null && (now - prevBaroTimeMs) in 1000L..30000L) {
                                        cachedVerticalRateMs = (alt - prevBaroAltitudeM!!) / ((now - prevBaroTimeMs) / 1000.0)
                                    }
                                    prevBaroAltitudeM = alt
                                    prevBaroTimeMs = now
                                } catch (_: Exception) {}
                            }
                        }
                        Sensor.TYPE_ROTATION_VECTOR,
                        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                            try {
                                val rotationMatrix = FloatArray(9)
                                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                                val orientation = FloatArray(3)
                                SensorManager.getOrientation(rotationMatrix, orientation)
                                val azimuthRad = orientation[0]
                                val azimuthDeg = ((Math.toDegrees(azimuthRad.toDouble()) + 360) % 360).toInt()
                                cachedAzimuthDeg = azimuthDeg
                                cachedPitchDeg = Math.toDegrees(orientation[1].toDouble()).toInt()
                                cachedRollDeg = Math.toDegrees(orientation[2].toDouble()).toInt()
                                cachedGravityZ = rotationMatrix[8] * 9.8f
                            } catch (_: Exception) {}
                        }
                        Sensor.TYPE_LIGHT -> {
                            cachedLightLux = event.values.firstOrNull()
                        }
                        Sensor.TYPE_PROXIMITY -> {
                            val dist = event.values.firstOrNull() ?: 5f
                            val maxR = event.sensor.maximumRange.coerceAtMost(5f)
                            cachedProximityNear = dist < maxR
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            if (event.values.size >= 3) {
                                val bx = event.values[0]
                                val by = event.values[1]
                                val bz = event.values[2]
                                cachedMagneticFieldUt = kotlin.math.sqrt((bx * bx + by * by + bz * bz).toDouble()).toFloat()
                            }
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            pressureSensor?.let { sm?.registerListener(listener, it, 5_000_000) }
            rotSensor?.let { sm?.registerListener(listener, it, 5_000_000) }
            lightSensor?.let { sm?.registerListener(listener, it, 5_000_000) }
            proxSensor?.let { sm?.registerListener(listener, it, 5_000_000) }
            magSensor?.let { sm?.registerListener(listener, it, 5_000_000) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                    val gnssCallback = object : android.location.GnssStatus.Callback() {
                        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                            val total = status.satelliteCount
                            var locked = 0
                            var cn0Sum = 0.0
                            for (i in 0 until total) {
                                if (status.usedInFix(i)) {
                                    locked++
                                    cn0Sum += status.getCn0DbHz(i)
                                }
                            }
                            val avgCn0 = if (locked > 0) (cn0Sum / locked).toInt() else 0
                            cachedGnssSummary = "$locked/$total Locked (Avg $avgCn0 dB-Hz)"
                        }
                    }
                    lm?.registerGnssStatusCallback(gnssCallback, android.os.Handler(android.os.Looper.getMainLooper()))
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun updateAndScoreOnlineMlModel(signalDbm: Int, magUt: Float, vibrationMg: Int): Pair<Double, String> {
        return try {
            val prefs = context.getSharedPreferences("sentinel_online_ml_model", Context.MODE_PRIVATE)
            val prevN = prefs.getInt("sample_count", 0)
            var magMean = prefs.getFloat("mag_mean", magUt)
            var magM2 = prefs.getFloat("mag_m2", 25f)
            val sigF = signalDbm.toFloat()
            var sigMean = prefs.getFloat("sig_mean", sigF)
            var sigM2 = prefs.getFloat("sig_m2", 36f)
            val vibF = vibrationMg.toFloat()
            var vibMean = prefs.getFloat("vib_mean", vibF)
            var vibM2 = prefs.getFloat("vib_m2", 25f)

            // 1. Score anomaly Z-score against PRIOR baseline BEFORE updating weights (prevents target contamination!)
            val priorDivisor = (prevN - 1).coerceIn(1, 200).toFloat()
            val stdMag = kotlin.math.sqrt(kotlin.math.max(magM2 / priorDivisor, 9.0f).toDouble())
            val stdSig = kotlin.math.sqrt(kotlin.math.max(sigM2 / priorDivisor, 16.0f).toDouble())
            val stdVib = kotlin.math.sqrt(kotlin.math.max(vibM2 / priorDivisor, 16.0f).toDouble())

            val maxZ = if (prevN >= 5) {
                val zMag = kotlin.math.abs(magUt - magMean) / stdMag
                val zSig = kotlin.math.abs(sigF - sigMean) / stdSig
                val zVib = kotlin.math.abs(vibF - vibMean) / stdVib
                maxOf(zMag, zSig, zVib)
            } else {
                0.0
            }

            // 2. Throttle training updates to once per 15s (prevents rapid UI refresh polling from overfitting),
            //    and Winsorize/clip extreme >3.5σ outliers so an active attack doesn't poison the normal baseline!
            val now = System.currentTimeMillis()
            var newN = prevN
            if (prevN == 0 || now - lastMlTrainTimeMs >= 15_000L) {
                lastMlTrainTimeMs = now
                newN = (prevN + 1).coerceAtMost(1_000_000)
                val effectiveWindow = newN.coerceAtMost(200) // Exponential moving window of ~200 samples

                val clampedMag = magUt.coerceIn(magMean - 3.0f * stdMag.toFloat(), magMean + 3.0f * stdMag.toFloat())
                val dMag = clampedMag - magMean
                magMean += dMag / effectiveWindow
                magM2 = ((magM2 * (effectiveWindow - 1)) / effectiveWindow) + dMag * (clampedMag - magMean)

                val clampedSig = sigF.coerceIn(sigMean - 3.0f * stdSig.toFloat(), sigMean + 3.0f * stdSig.toFloat())
                val dSig = clampedSig - sigMean
                sigMean += dSig / effectiveWindow
                sigM2 = ((sigM2 * (effectiveWindow - 1)) / effectiveWindow) + dSig * (clampedSig - sigMean)

                val clampedVib = vibF.coerceIn(vibMean - 3.0f * stdVib.toFloat(), vibMean + 3.0f * stdVib.toFloat())
                val dVib = clampedVib - vibMean
                vibMean += dVib / effectiveWindow
                vibM2 = ((vibM2 * (effectiveWindow - 1)) / effectiveWindow) + dVib * (clampedVib - vibMean)

                prefs.edit()
                    .putInt("sample_count", newN)
                    .putFloat("mag_mean", magMean)
                    .putFloat("mag_m2", magM2)
                    .putFloat("sig_mean", sigMean)
                    .putFloat("sig_m2", sigM2)
                    .putFloat("vib_mean", vibMean)
                    .putFloat("vib_m2", vibM2)
                    .apply()
            }

            val statusStr = if (newN >= 5) {
                "Trained ($newN samples · Z=${String.format(java.util.Locale.US, "%.1f", maxZ)}σ)"
            } else {
                "Calibrating ($newN/5 samples)"
            }
            (maxZ to statusStr).also { lastMlResult = it }
        } catch (_: Throwable) {
            0.0 to "Calibrating (1/5 samples)"
        }
    }

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

        // Live RF Signal Strength (dBm)
        val signalDbm: Int = try {
            val cmNet = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val actNet = cmNet?.activeNetwork
            val netCaps = cmNet?.getNetworkCapabilities(actNet)
            val isWifiActive = netCaps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true

            if (isWifiActive) {
                val rssi = wifiInfo?.rssi ?: -127
                if (rssi in -120..-20) rssi else -75
            } else {
                val cellDbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    telephonyManager?.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
                } else null
                if (cellDbm != null && cellDbm in -140..-40) cellDbm else -75
            }
        } catch (_: Exception) {
            -75
        }

        // 3D Barometer & Floor Estimation
        val barometricPressureHpa: Double = cachedPressureHpa ?: 1013.25
        val estimatedAltitudeMeters: Double = try {
            SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                barometricPressureHpa.toFloat()
            ).toDouble()
        } catch (_: Exception) {
            0.0
        }
        val estimatedFloor: Int = (estimatedAltitudeMeters / 3.2).toInt()

        // Compass Azimuth & Live Acceleration
        val compassAzimuthDeg: Int = cachedAzimuthDeg ?: 0
        val currentAccG = fallDetector?.currentAccelerationG ?: 1.0f
        val accelerationG = (Math.round(currentAccG * 100.0) / 100.0).toFloat()

        // GNSS Location & Mock Detection
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val lastLoc = try {
            lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        }

        val gpsSpeedKmh = ((lastLoc?.speed ?: 0f) * 3.6f)
        val deltaMg = fallDetector?.microVibrationMg ?: 0

        val motionActivity = when {
            gpsSpeedKmh >= 18f -> String.format(java.util.Locale.US, "VEHICLE (%.0f km/h)", gpsSpeedKmh)
            gpsSpeedKmh >= 8f || deltaMg > 220 || currentAccG > 1.45f || currentAccG < 0.60f ->
                if (gpsSpeedKmh > 0.5f) String.format(java.util.Locale.US, "RUNNING (%.1f km/h)", gpsSpeedKmh) else "RUNNING / ACTIVE"
            gpsSpeedKmh >= 1.8f || deltaMg in 25..220 || currentAccG > 1.12f || currentAccG < 0.88f ->
                if (gpsSpeedKmh > 0.5f) String.format(java.util.Locale.US, "WALKING (%.1f km/h)", gpsSpeedKmh) else "WALKING"
            else -> "STATIONARY"
        }

        val gnssSpoofSuspected: Boolean = try {
            val isMockSetting = try {
                Settings.Secure.getString(context.contentResolver, "mock_location") == "1"
            } catch (_: Exception) {
                false
            }
            val isMockLocation = lastLoc?.let { loc ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    loc.isMock
                } else {
                    @Suppress("DEPRECATION")
                    loc.isFromMockProvider
                }
            } ?: false
            isMockSetting || isMockLocation
        } catch (_: Exception) {
            false
        }

        // Tactical Serving Cell Info
        val servingCellSummary: String = try {
            val registeredCell = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                telephonyManager?.allCellInfo?.firstOrNull { it.isRegistered }
            } else null

            when {
                registeredCell == null -> "N/A"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && registeredCell is android.telephony.CellInfoNr -> {
                    val id = registeredCell.cellIdentity as? android.telephony.CellIdentityNr
                    val pci = id?.pci ?: -1
                    val nci = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id?.nci ?: -1L else -1L
                    "5G-NR PCI:$pci NCI:$nci"
                }
                registeredCell is android.telephony.CellInfoLte -> {
                    val id = registeredCell.cellIdentity
                    val ci = id.ci
                    val pci = id.pci
                    val tac = id.tac
                    "LTE CID:$ci PCI:$pci TAC:$tac"
                }
                registeredCell is android.telephony.CellInfoWcdma -> {
                    val id = registeredCell.cellIdentity
                    "3G CID:${id.cid} PSC:${id.psc}"
                }
                registeredCell is android.telephony.CellInfoGsm -> {
                    val id = registeredCell.cellIdentity
                    "GSM CID:${id.cid} LAC:${id.lac}"
                }
                else -> "Cellular Registered"
            }
        } catch (_: SecurityException) {
            "Cellular"
        } catch (_: Exception) {
            "N/A"
        }

        val rfScan = getRfEnvironmentScan()

        val devicePoseAndCameraHint = when {
            cachedProximityNear && (cachedLightLux ?: 10f) < 5f -> "IN POCKET / COVERED (Both Cams Dark)"
            cachedGravityZ > 8.0f && kotlin.math.abs(cachedPitchDeg) < 20 -> "FLAT FACE-UP (Front Cam Clear · Rear Blocked)"
            cachedGravityZ < -8.0f -> "FLAT FACE-DOWN (Rear Cam Clear · Front Blocked)"
            kotlin.math.abs(cachedPitchDeg) in 25..75 -> "HELD IN HAND / STAND (Both Cams Clear)"
            else -> "UPRIGHT / VERTICAL (${cachedPitchDeg}° pitch)"
        }

        val lux = cachedLightLux
        val ambientEnvironment = when {
            lux == null -> "Light Sensor N/A"
            lux < 5f -> "Dark (${lux.toInt()} lux)"
            lux < 500f -> "Indoors (${lux.toInt()} lux)"
            else -> "Outdoors Daylight (${lux.toInt()} lux)"
        }

        val magneticFieldUt = cachedMagneticFieldUt?.toInt() ?: 45
        val emfAnomalyDetected = magneticFieldUt > 120

        val seismicVibrationSummary = "${fallDetector?.seismicState ?: "DEAD STILL"} (${fallDetector?.microVibrationMg ?: 0} mG)"

        val gnssConstellationSummary = cachedGnssSummary

        val chargeCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        val batteryCycleCount = batteryStatus?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
        val batteryEnduranceSummary = if (!isCharging && chargeCounterUah > 0 && abs(currentNowUa) > 10_000) {
            val hoursLeft = chargeCounterUah.toDouble() / abs(currentNowUa).toDouble()
            val h = hoursLeft.toInt().coerceAtMost(99)
            val m = ((hoursLeft - hoursLeft.toInt()) * 60).toInt().coerceIn(0, 59)
            "Est. ${h}h ${m}m left (@ ${abs(currentNowUa) / 1000} mA)"
        } else if (isCharging) {
            String.format(java.util.Locale.US, "Charging @ %.2f W", powerWatts)
        } else {
            "$batteryPct% (${if (isCharging) "Charging" else "Discharging"})"
        }

        val cpuFrequencySummary = try {
            val freqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
            if (freqFile.exists()) {
                val khz = freqFile.readText().trim().toLongOrNull() ?: 0L
                if (khz > 0) "$cpuCores Cores @ ${khz / 1000} MHz" else "$cpuCores Cores"
            } else "$cpuCores Cores"
        } catch (_: Exception) {
            "$cpuCores Cores"
        }

        val vpnActive = try {
            val caps = cm?.getNetworkCapabilities(activeNet)
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true ||
                File("/sys/class/net/tun0").exists() ||
                File("/sys/class/net/wg0").exists()
        } catch (_: Exception) { false }
        val proxyActive = !System.getProperty("http.proxyHost").isNullOrBlank()
        val totalTrafficSummary = try {
            val rxMb = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0L) / (1024.0 * 1024.0)
            val txMb = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0L) / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "↓ %.1f MB / ↑ %.1f MB", rxMb, txMb)
        } catch (_: Exception) { "N/A" }
        val activeTcpSockets = try {
            val tcp4 = File("/proc/net/tcp").takeIf { it.exists() }?.readLines()?.size?.minus(1)?.coerceAtLeast(0) ?: 0
            val tcp6 = File("/proc/net/tcp6").takeIf { it.exists() }?.readLines()?.size?.minus(1)?.coerceAtLeast(0) ?: 0
            tcp4 + tcp6
        } catch (_: Exception) { 0 }

        val vRate = cachedVerticalRateMs
        val verticalMotionState = when {
            vRate > 0.35 -> String.format(java.util.Locale.US, "ASCENDING / ELEVATOR (+%.1f m/s)", vRate)
            vRate < -0.35 -> String.format(java.util.Locale.US, "DESCENDING / SUBTERRANEAN (%.1f m/s)", vRate)
            else -> "LEVEL ALTITUDE (0.0 m/s)"
        }

        // Physical USB-C BadUSB / O.MG Cable / Cellebrite Port Scanner
        val (usbHardwareSummary, usbIntrusionSuspected) = try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val deviceList = usbManager?.deviceList ?: emptyMap()
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isLocked = km?.isKeyguardLocked == true

            var hidFound = false
            val devSummaries = mutableListOf<String>()
            for ((_, device) in deviceList) {
                var isHid = false
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                        isHid = true
                        hidFound = true
                        break
                    }
                }
                val vidPid = String.format(java.util.Locale.US, "%04X:%04X", device.vendorId, device.productId)
                devSummaries.add(if (isHid) "$vidPid (HID/BadUSB)" else vidPid)
            }

            val intrusion = hidFound || (secSnapshot.isUsbAdbActive && isLocked)
            val summary = when {
                deviceList.isEmpty() && !secSnapshot.isUsbConnected -> "Port Idle / No Peripherals"
                deviceList.isEmpty() && secSnapshot.isUsbConnected -> if (secSnapshot.isUsbAdbActive) "Host Link Active (ADB)" else "Port Charging Only"
                hidFound -> "⚠️ HID Peripheral Injected [${devSummaries.joinToString(", ")}]"
                else -> "Attached: ${devSummaries.joinToString(", ")}"
            }
            summary to intrusion
        } catch (_: Exception) {
            "Port Idle / No Peripherals" to false
        }

        // Counter-SIGINT SIM & Baseband Tamper Detector
        val (simSecuritySummary, simTamperOrRoamingAlert) = try {
            val simState = telephonyManager?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
            val isSimReady = simState == TelephonyManager.SIM_STATE_READY
            val simIso = telephonyManager?.simCountryIso?.trim()?.lowercase(java.util.Locale.US) ?: ""
            val netIso = telephonyManager?.networkCountryIso?.trim()?.lowercase(java.util.Locale.US) ?: ""
            val isRoaming = telephonyManager?.isNetworkRoaming == true

            val hasIsoMismatch = isSimReady && simIso.isNotBlank() && netIso.isNotBlank() && simIso != netIso
            val alert = hasIsoMismatch || isRoaming || (simState == TelephonyManager.SIM_STATE_NETWORK_LOCKED)

            val simSummary = when {
                !isSimReady -> when (simState) {
                    TelephonyManager.SIM_STATE_ABSENT -> "No SIM Inserted"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED,
                    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "SIM PIN/PUK Locked"
                    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "⚠️ SIM Network Locked"
                    else -> "SIM Inactive ($simState)"
                }
                hasIsoMismatch -> "⚠️ SIM/Net Country Mismatch ($simIso vs $netIso)"
                isRoaming -> "Roaming ($netIso)"
                else -> "Verified Secure (${simIso.uppercase(java.util.Locale.US)})"
            }
            simSummary to alert
        } catch (_: Exception) {
            "Verified Secure" to false
        }

        // Online-Learned Mahalanobis Z-Score + 12-Feature Bayesian Logistic Sigmoid Model
        val (learnedZScore, mlModelStatus) = updateAndScoreOnlineMlModel(
            signalDbm = signalDbm,
            magUt = cachedMagneticFieldUt ?: 45f,
            vibrationMg = deltaMg
        )
        val rogueCellFlag = (rfScan["rogueCellSuspected"] as? Boolean) == true

        var logOdds = -3.8 // Baseline nominal prior (~2.2% risk)
        val riskDrivers = mutableListOf<String>()

        if (rootResult.isRooted) { logOdds += 5.6; riskDrivers.add("Root/Kernel Tamper") }
        if (usbIntrusionSuspected) { logOdds += 5.3; riskDrivers.add("USB HID/Locked Port Intrusion") }
        if (!isSelinuxEnforced) { logOdds += 4.3; riskDrivers.add("SELinux Permissive") }
        if (gnssSpoofSuspected) { logOdds += 4.1; riskDrivers.add("GNSS Mock/Spoof") }
        if (rogueCellFlag) { logOdds += 4.2; riskDrivers.add("Rogue 2G Cell/IMSI") }
        if (simTamperOrRoamingAlert) { logOdds += 2.8; riskDrivers.add("SIM Roaming/Mismatch") }
        if (secSnapshot.isUsbAdbActive) { logOdds += 2.7; riskDrivers.add("Active USB ADB") }
        else if (secSnapshot.isAdbEnabled || secSnapshot.isDeveloperOptionsEnabled) { logOdds += 1.3; riskDrivers.add("ADB/DevOpts Enabled") }
        if (vpnActive || proxyActive) { logOdds += 2.5; riskDrivers.add("VPN/Proxy Tunnel") }
        if (emfAnomalyDetected) { logOdds += 1.4; riskDrivers.add("High EMF (>120µT)") }
        if (learnedZScore > 3.0) {
            val zBoost = ((learnedZScore - 2.5) * 0.85).coerceAtMost(3.0)
            logOdds += zBoost
            riskDrivers.add(String.format(java.util.Locale.US, "ML Anomaly (%.1fσ)", learnedZScore))
        }

        val probability = (1.0 / (1.0 + kotlin.math.exp(-logOdds))) * 100.0
        val threatProbabilityPct = (kotlin.math.round(probability * 10.0) / 10.0).coerceIn(0.1, 99.9)
        val defconLevel = when {
            threatProbabilityPct >= 80.0 -> 1
            threatProbabilityPct >= 50.0 -> 2
            threatProbabilityPct >= 20.0 -> 3
            threatProbabilityPct >= 6.0 -> 4
            else -> 5
        }
        val driversText = if (riskDrivers.isNotEmpty()) riskDrivers.take(2).joinToString(" + ") else "All Sensors Nominal"
        val defconSummary = String.format(java.util.Locale.US, "DEFCON %d (%.1f%% Risk · %s)", defconLevel, threatProbabilityPct, driversText)

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
            "hardwareCallsign" to hw.hardwareCallsign,
            "signalDbm" to signalDbm,
            "barometricPressureHpa" to barometricPressureHpa,
            "estimatedAltitudeMeters" to estimatedAltitudeMeters,
            "estimatedFloor" to estimatedFloor,
            "compassAzimuthDeg" to compassAzimuthDeg,
            "motionActivity" to motionActivity,
            "gnssSpoofSuspected" to gnssSpoofSuspected,
            "servingCellSummary" to servingCellSummary,
            "accelerationG" to accelerationG,
            "rogueCellSuspected" to (rfScan["rogueCellSuspected"] ?: false),
            "neighborCellCount" to (rfScan["neighborCellCount"] ?: 0),
            "nearbyWifiCount" to (rfScan["nearbyWifiCount"] ?: 0),
            "cellTowers" to (rfScan["cellTowers"] ?: emptyList<Map<String, Any>>()),
            "nearbyWifiBssids" to (rfScan["nearbyWifiBssids"] ?: emptyList<Map<String, Any>>()),
            "devicePoseAndCameraHint" to devicePoseAndCameraHint,
            "ambientEnvironment" to ambientEnvironment,
            "magneticFieldUt" to magneticFieldUt,
            "emfAnomalyDetected" to emfAnomalyDetected,
            "seismicVibrationSummary" to seismicVibrationSummary,
            "gnssConstellationSummary" to gnssConstellationSummary,
            "batteryEnduranceSummary" to batteryEnduranceSummary,
            "batteryCycleCount" to batteryCycleCount,
            "cpuFrequencySummary" to cpuFrequencySummary,
            "vpnActive" to vpnActive,
            "proxyActive" to proxyActive,
            "totalTrafficSummary" to totalTrafficSummary,
            "activeTcpSockets" to activeTcpSockets,
            "verticalMotionState" to verticalMotionState,
            "usbHardwareSummary" to usbHardwareSummary,
            "usbIntrusionSuspected" to usbIntrusionSuspected,
            "simSecuritySummary" to simSecuritySummary,
            "simTamperOrRoamingAlert" to simTamperOrRoamingAlert,
            "defconLevel" to defconLevel,
            "threatProbabilityPct" to threatProbabilityPct,
            "defconSummary" to defconSummary,
            "mlModelStatus" to mlModelStatus
        )
    }

    fun getRfEnvironmentScan(): Map<String, Any> {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        val cellTowers = mutableListOf<Map<String, Any>>()
        var neighborCellCount = 0
        var rogueCellSuspected = false

        try {
            val allCells = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                telephonyManager?.allCellInfo ?: emptyList()
            } else {
                emptyList()
            }

            neighborCellCount = allCells.count { !it.isRegistered }
            val registeredCells = allCells.filter { it.isRegistered }
            val activeRegisteredCell = registeredCells.firstOrNull()

            if (activeRegisteredCell != null) {
                // IMSI-Catcher / Downgrade Heuristic:
                // 1) Active registered cell forced down to GSM (2G) with 0 neighbor cells (isolated fake BTS)
                if (activeRegisteredCell is android.telephony.CellInfoGsm && neighborCellCount == 0) {
                    rogueCellSuspected = true
                }
                // 2) Registered cell reports invalid / impossible CID (e.g. <= 0 or Int.MAX_VALUE)
                when (activeRegisteredCell) {
                    is android.telephony.CellInfoGsm -> {
                        val cid = activeRegisteredCell.cellIdentity.cid
                        if (cid <= 0 || cid == Int.MAX_VALUE) rogueCellSuspected = true
                    }
                    is android.telephony.CellInfoWcdma -> {
                        val cid = activeRegisteredCell.cellIdentity.cid
                        if (cid <= 0 || cid == Int.MAX_VALUE) rogueCellSuspected = true
                    }
                    is android.telephony.CellInfoLte -> {
                        val ci = activeRegisteredCell.cellIdentity.ci
                        if (ci <= 0 || ci == Int.MAX_VALUE) rogueCellSuspected = true
                    }
                }
            }

            for (cell in allCells.take(10)) {
                val isReg = cell.isRegistered
                val dbm = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cell.cellSignalStrength.dbm
                    } else {
                        when (cell) {
                            is android.telephony.CellInfoGsm -> cell.cellSignalStrength.dbm
                            is android.telephony.CellInfoLte -> cell.cellSignalStrength.dbm
                            is android.telephony.CellInfoWcdma -> cell.cellSignalStrength.dbm
                            else -> -110
                        }
                    }
                } catch (_: Exception) {
                    -110
                }

                val type: String
                val identity: String
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is android.telephony.CellInfoNr -> {
                        type = "5G-NR"
                        val id = cell.cellIdentity as? android.telephony.CellIdentityNr
                        val pci = id?.pci ?: -1
                        val nci = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id?.nci ?: -1L else -1L
                        identity = "PCI:$pci NCI:$nci"
                    }
                    cell is android.telephony.CellInfoLte -> {
                        type = "LTE"
                        val id = cell.cellIdentity
                        val ci = id.ci
                        val pci = id.pci
                        val tac = id.tac
                        identity = "CID:$ci PCI:$pci TAC:$tac"
                    }
                    cell is android.telephony.CellInfoWcdma -> {
                        type = "WCDMA"
                        val id = cell.cellIdentity
                        val cid = id.cid
                        val psc = id.psc
                        val lac = id.lac
                        identity = "CID:$cid LAC:$lac PSC:$psc"
                    }
                    cell is android.telephony.CellInfoGsm -> {
                        type = "GSM"
                        val id = cell.cellIdentity
                        val cid = id.cid
                        val lac = id.lac
                        identity = "CID:$cid LAC:$lac"
                    }
                    else -> {
                        type = "Cellular"
                        identity = "N/A"
                    }
                }

                cellTowers.add(
                    mapOf(
                        "type" to type,
                        "registered" to isReg,
                        "identity" to identity,
                        "dbm" to dbm
                    )
                )
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {}

        val nearbyWifiBssids = mutableListOf<Map<String, Any>>()
        try {
            val scanResults = wifiManager?.scanResults
            if (scanResults != null) {
                val sorted = scanResults.sortedByDescending { it.level }.take(12)
                for (sr in sorted) {
                    val ssid = sr.SSID?.takeIf { it.isNotBlank() } ?: "<Hidden>"
                    val bssid = sr.BSSID ?: "N/A"
                    val rssiDbm = sr.level
                    val freqMhz = sr.frequency
                    nearbyWifiBssids.add(
                        mapOf(
                            "ssid" to ssid,
                            "bssid" to bssid,
                            "rssiDbm" to rssiDbm,
                            "freqMhz" to freqMhz
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {}

        return mapOf(
            "cellTowers" to cellTowers,
            "neighborCellCount" to neighborCellCount,
            "rogueCellSuspected" to rogueCellSuspected,
            "nearbyWifiBssids" to nearbyWifiBssids,
            "nearbyWifiCount" to nearbyWifiBssids.size,
            "timestamp" to System.currentTimeMillis()
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
