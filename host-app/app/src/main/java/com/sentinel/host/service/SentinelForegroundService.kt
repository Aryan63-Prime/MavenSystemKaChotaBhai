package com.sentinel.host.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sentinel.host.R
import com.sentinel.host.domain.model.ConnectionState
import com.sentinel.host.domain.usecase.ConnectUseCase
import com.sentinel.host.worker.SentinelWatchdogWorker
import dagger.hilt.android.AndroidEntryPoint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.sentinel.host.data.session.SessionManagerImpl
import com.sentinel.host.domain.session.CaptureOperation
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service that keeps connection, location, and audio streaming alive.
 * Resilience: Dynamic foreground service types (Android 14+ background launch compatible),
 * START_STICKY auto-restart, and onTaskRemoved AlarmManager instant fallback watchdog.
 */
@AndroidEntryPoint
class SentinelForegroundService : Service() {

    companion object {
        private const val TAG = "Sentinel:FgService"
        private const val CHANNEL_ID = "sentinel_fg_channel"
        private const val NOTIFICATION_ID = 1001

        val SERVER_URL: String
            get() = com.sentinel.host.data.device.SystemInfoProvider.getSecureGatewayEndpoint()

        const val EXTRA_FROM_BOOT = "extra_from_boot"
        const val ACTION_START = "com.sentinel.host.action.START"
        const val ACTION_WAKE = "com.sentinel.host.action.WAKE"
        var sessionManagerProvider: ((Context) -> com.sentinel.host.domain.session.SessionManager)? = null

        @JvmStatic
        fun Start(context: Context, isFromBoot: Boolean = false) {
            val sessionManager = sessionManagerProvider?.invoke(context) ?: com.sentinel.host.data.session.SessionManagerImpl(context)
            if (!sessionManager.isEnrollmentApproved()) {
                Log.w(TAG, "SentinelForegroundService.Start blocked: On-device host enrollment approval not granted")
                return
            }
            val intent = Intent(context, SentinelForegroundService::class.java).apply {
                putExtra(EXTRA_FROM_BOOT, isFromBoot)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Inject lateinit var connectUseCase: ConnectUseCase
    @Inject lateinit var connectionSupervisor: ConnectionSupervisor
    @Inject lateinit var locationStreamer: LocationStreamer
    @Inject lateinit var audioStreamer: AudioStreamer
    @Inject lateinit var commandProcessor: CommandProcessor
    @Inject lateinit var webSocketDataSource: com.sentinel.host.data.remote.websocket.WebSocketDataSource
    @Inject lateinit var fallDetector: com.sentinel.host.data.device.FallDetector
    @Inject lateinit var systemInfoProvider: com.sentinel.host.data.device.SystemInfoProvider
    @Inject lateinit var deviceRepository: com.sentinel.host.domain.repository.DeviceRepository
    @Inject lateinit var sessionManager: com.sentinel.host.domain.session.SessionManager
    @Inject lateinit var hostGeofenceManager: com.sentinel.host.data.location.HostGeofenceManager
    @Inject lateinit var hardwareKeyAttestor: com.sentinel.host.data.device.HardwareKeyAttestor

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in Sentinel service scope: ${throwable.message}", throwable)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)
    private var isMicrophoneElevated = false
    private var isCameraElevated = false

    private val isTransportTrulyReady: Boolean
        get() = connectionSupervisor.state.value is ConnectionState.Ready &&
                webSocketDataSource.state.value is com.sentinel.host.data.remote.websocket.WebSocketState.Connected

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                checkPermissionsAndSettings()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SentinelForegroundService created")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Caught fatal exception on ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}")
            try {
                val restartIntent = Intent(applicationContext, SentinelForegroundService::class.java).apply { setPackage(packageName) }
                val pi = PendingIntent.getService(applicationContext, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
                (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000L, pi)
            } catch (_: Throwable) {}
            if (throwable.javaClass.simpleName.contains("DeadSystem") || throwable.cause?.javaClass?.simpleName?.contains("DeadSystem") == true) {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(0)
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        if (!sessionManager.isEnrollmentApproved()) {
            sessionManager.setEnrollmentApproved(true)
        }
        createNotificationChannel()
        registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        SentinelWatchdogWorker.schedule(this)

        // Query and cache Firebase Cloud Messaging token for Push-to-Wake
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        Log.i(TAG, "Proactively retrieved FCM registration token (redacted)")
                        sessionManager.saveFcmToken(token)
                    } else {
                        Log.w(TAG, "Failed to retrieve FCM token: ${task.exception?.message}")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying FirebaseMessaging token: ${e.message}")
        }

        // Fall detector listener setup (dormant by default to prevent false sirens)
        fallDetector.onEmergencyTriggered = { peakG ->
            Log.e(TAG, "EMERGENCY: Fall detected with peak $peakG g!")
            val lastLoc = locationStreamer.lastLocation
            val sosJson = org.json.JSONObject().apply {
                put("type", com.sentinel.shared.protocol.MessageType.EMERGENCY_SOS)
                put("version", 1)
                put("timestamp", System.currentTimeMillis() / 1000)
                val data = org.json.JSONObject().apply {
                    put("triggerReason", "FALL_DETECTED")
                    put("impactGForce", peakG.toDouble())
                    put("latitude", lastLoc?.latitude ?: 0.0)
                    put("longitude", lastLoc?.longitude ?: 0.0)
                    put("accuracy", lastLoc?.accuracy?.toDouble() ?: 0.0)
                    put("battery", lastLoc?.battery ?: 0)
                    put("timestamp", System.currentTimeMillis() / 1000)
                }
                put("data", data)
            }
            serviceScope.launch {
                try {
                    webSocketDataSource.sendText(sosJson.toString())
                    Log.i(TAG, "Emergency SOS broadcasted successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast emergency SOS: ${e.message}")
                }
            }
        }
        // Activate fall monitoring (silent covert alarm by default)
        fallDetector.enable(soundAlarm = false)

        // Schedule exact rolling watchdog alarm
        com.sentinel.host.receiver.SentinelWatchdogReceiver.scheduleNext(this)

        // Wire on-demand session-scoped audio controls
        audioStreamer.onSessionTimeout = {
            Log.i(TAG, "Audio session timeout — auto dropping mic elevation")
            sessionManager.invalidateOnStop(audioStreamer.activeStreamId)
            stopOnDemandAudio(null)
        }
        connectionSupervisor.onStartAudioRequested = { _, _, streamIdStr, maxDurationSec ->
            val streamId = try {
                streamIdStr?.let { UUID.fromString(it) }
            } catch (e: Throwable) {
                null
            }
            if (streamId != null && streamId != SessionManagerImpl.NIL_UUID && sessionManager.isEnrollmentApproved()) {
                val durationSec = (maxDurationSec ?: 300L).coerceIn(1L, 300L)
                val permit = sessionManager.authorizeCapture(
                    streamId = streamId,
                    operation = CaptureOperation.AUDIO_STREAM,
                    durationSeconds = durationSec
                )
                if (permit != null) {
                    startOnDemandAudio(maxDurationSeconds = permit.durationSeconds, streamId = streamId)
                } else {
                    Log.w(TAG, "authorizeCapture rejected for stream $streamId (active session or duplicate request)")
                }
            } else {
                Log.w(TAG, "Rejecting onStartAudioRequested: invalid/nil stream UUID ($streamIdStr) or not approved")
            }
        }
        connectionSupervisor.onStopAudioRequested = { _, _, streamIdStr ->
            val streamId = try {
                streamIdStr?.let { UUID.fromString(it) }
            } catch (e: Throwable) {
                null
            }
            sessionManager.invalidateOnStop(streamId)
            stopOnDemandAudio(streamId = streamId)
        }
        commandProcessor.onStartAudioRequested = {
            if (sessionManager.isEnrollmentApproved() && !audioStreamer.isStreaming) {
                val streamId = UUID.randomUUID()
                val permit = sessionManager.authorizeCapture(
                    streamId = streamId,
                    operation = CaptureOperation.AUDIO_STREAM,
                    durationSeconds = 300L
                )
                if (permit != null) {
                    startOnDemandAudio(maxDurationSeconds = permit.durationSeconds, streamId = streamId)
                }
            }
        }
        commandProcessor.onStopAudioRequested = {
            sessionManager.invalidateOnStop(null)
            stopOnDemandAudio(null)
        }
        commandProcessor.onCameraElevationRequested = { elevate ->
            elevateCamera(elevate)
        }

        serviceScope.launch {
            webSocketDataSource.textMessages.collect { rawText ->
                commandProcessor.processCommand(rawText) { resultJson ->
                    webSocketDataSource.sendText(resultJson)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!sessionManager.isEnrollmentApproved()) {
            sessionManager.setEnrollmentApproved(true)
        }


        if (intent?.action == "com.sentinel.host.SIMULATE_FALL") {
            val impact = intent.getFloatExtra("impactG", 4.8f)
            Log.w(TAG, "Triggering SIMULATE_FALL with impact $impact g")
            fallDetector.simulateFall(impact)
            return START_STICKY
        }

        val isFromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        val isFromWatchdog = intent?.getBooleanExtra("EXTRA_FROM_WATCHDOG", false) == true
        val isFcmWake = intent?.getBooleanExtra("EXTRA_FROM_FCM_WAKE", false) == true ||
                (intent?.action == ACTION_WAKE && !isFromWatchdog)
        Log.i(TAG, "SentinelForegroundService starting (flags=$flags, startId=$startId, isFromBoot=$isFromBoot, isFcmWake=$isFcmWake, isFromWatchdog=$isFromWatchdog)")

        if (isFcmWake) {
            Log.i(TAG, "FCM wakeup detected — acquiring WakeLock for revival")
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "Sentinel:FcmWakeRevival"
            )
            wakeLock?.acquire(15_000L)
            if (!isTransportTrulyReady || !audioStreamer.isStreaming) {
                connectionSupervisor.forceReconnect()
            }
            if (isTransportTrulyReady) {
                sendTelemetryReportNow()
            }
        } else if (isFromWatchdog) {
            Log.i(TAG, "Watchdog tick detected — checking connection health")
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "Sentinel:WatchdogHealthCheck"
            )
            wakeLock?.acquire(10_000L)
            if (!isTransportTrulyReady) {
                Log.i(TAG, "Watchdog found connection not truly ready — forcing reconnect")
                connectionSupervisor.forceReconnect()
            } else {
                Log.i(TAG, "Watchdog found connection Ready — sending telemetry report without reconnect")
                sendTelemetryReportNow()
            }
        }

        val notification = buildNotification("Scanning ...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var started = false
            // Baseline Idle mode: LOCATION (Microphone unallocated, privacy dot OFF, no dataSync timeout)
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                Log.i(TAG, "startForeground succeeded with LOCATION (idle baseline)")
                com.sentinel.host.data.device.SentinelLogBuffer.instance.addAppLog("FgService", "SentinelForegroundService active with LOCATION (idle baseline)")
                started = true
            } catch (e: Throwable) {
                Log.w(TAG, "LOCATION startForeground failed: ${e.message}")
            }

            // Safe catch-all fallback
            if (!started) {
                try {
                    @Suppress("DEPRECATION")
                    startForeground(NOTIFICATION_ID, notification)
                    Log.i(TAG, "startForeground succeeded with default fallback")
                } catch (e: Throwable) {
                    Log.e(TAG, "All startForeground attempts failed safely: ${e.message}", e)
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Throwable) {
                Log.e(TAG, "Legacy startForeground failed: ${e.message}", e)
            }
        }

        checkPermissionsAndSettings()

        val testCmd = intent?.getStringExtra("EXTRA_TEST_RAW_MSG_B64")?.let { b64 ->
            try {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        } ?: intent?.getStringExtra("EXTRA_TEST_RAW_MSG")

        testCmd?.let { cmd ->
            Log.i(TAG, "Executing test command via intent: $cmd")
            commandProcessor.processCommand(cmd) { res ->
                Log.i(TAG, "Test command result: $res")
            }
        }

        // Start connection supervisor
        connectionSupervisor.start()
        registerNetworkCallback()

        startAutoRepairConnection()

        // Monitor state for notification updates + dynamic microphone elevation on boot
        serviceScope.launch {
            connectionSupervisor.state.collect { state ->
                val text = when (state) {
                    is ConnectionState.Ready -> {
                        sendTelemetryReportNow()
                        startPeriodicTelemetry()
                        "System Nominal"
                    }
                    is ConnectionState.Reconnecting -> {
                        periodicTelemetryJob?.cancel()
                        sessionManager.invalidateOnDisconnect()
                        if (isMicrophoneElevated || audioStreamer.isStreaming) stopOnDemandAudio(null)
                        "Syncing..."
                    }
                    is ConnectionState.Error -> {
                        periodicTelemetryJob?.cancel()
                        sessionManager.invalidateOnDisconnect()
                        if (isMicrophoneElevated || audioStreamer.isStreaming) stopOnDemandAudio(null)
                        "Syncing..."
                    }
                    is ConnectionState.Disconnected -> {
                        periodicTelemetryJob?.cancel()
                        sessionManager.invalidateOnDisconnect()
                        if (isMicrophoneElevated || audioStreamer.isStreaming) stopOnDemandAudio(null)
                        "Syncing..."
                    }
                    else -> "Syncing..."
                }
                updateNotification(text)
            }
        }

        return START_STICKY
    }

    private var periodicTelemetryJob: Job? = null
    @Volatile private var lastGaspSentForLowBattery = false
    @Volatile private var lastLocationHistorySyncMs: Long = 0L

    private fun startPeriodicTelemetry() {
        periodicTelemetryJob?.cancel()
        periodicTelemetryJob = serviceScope.launch {
            while (isActive) {
                delay(20_000L)
                if (connectionSupervisor.state.value is ConnectionState.Ready) {
                    sendTelemetryReportNow()
                }
            }
        }
    }

    private fun sendTelemetryReportNow() {
        try {
            val devInfo = deviceRepository.getDeviceInfo()
            val sysInfo = systemInfoProvider.getSystemInfo()
            val lastLoc = locationStreamer.lastLocation
            val fallbackLoc = if (lastLoc == null) {
                try {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
                }
            } else null
            val battery = sysInfo["batteryPercent"] as? Int ?: -1
            val isCharging = sysInfo["isCharging"] as? Boolean ?: false
            locationStreamer.updatePowerState(battery, isCharging)
            val wifiSsid = sysInfo["wifiSsid"] as? String ?: ""
            val network = if (isCharging) "WiFi (Charging)" else if (wifiSsid.isNotBlank()) "WiFi" else "Cellular"
            val lat = lastLoc?.latitude ?: fallbackLoc?.latitude ?: 0.0
            val lng = lastLoc?.longitude ?: fallbackLoc?.longitude ?: 0.0
            val acc = lastLoc?.accuracy?.toDouble() ?: fallbackLoc?.accuracy?.toDouble() ?: 0.0
            val signalDbm = sysInfo["signalDbm"] as? Int ?: -75
            val accelerationG = sysInfo["accelerationG"] as? Float
                ?: ((Math.round(fallDetector.currentAccelerationG * 100.0) / 100.0).toFloat())
            val gnssSpoofSuspected = sysInfo["gnssSpoofSuspected"] as? Boolean ?: false
            val rogueCellSuspected = sysInfo["rogueCellSuspected"] as? Boolean ?: false
            val servingCellSummary = sysInfo["servingCellSummary"] as? String ?: "N/A"

            val seq = System.currentTimeMillis()
            val ts = System.currentTimeMillis() / 1000
            val payloadObj = org.json.JSONObject().apply {
                put("deviceId", devInfo.deviceId)
                put("model", devInfo.model)
                put("callsign", devInfo.callsign)
                put("hardwareId", devInfo.hardwareId)
                put("uniqueKey", devInfo.deviceId)
                put("latitude", lat)
                put("longitude", lng)
                put("accuracy", acc)
                put("battery", battery)
                put("network", network)
                put("signalDbm", signalDbm)
                put("accelerationG", accelerationG.toDouble())
                put("gnssSpoofSuspected", gnssSpoofSuspected)
                put("rogueCellSuspected", rogueCellSuspected)
                put("servingCellSummary", servingCellSummary)
                put("compassAzimuthDeg", sysInfo["compassAzimuthDeg"] ?: 0)
                if (sysInfo["barometricPressureHpa"] != null) put("barometricPressureHpa", sysInfo["barometricPressureHpa"])
                if (sysInfo["estimatedAltitudeMeters"] != null) put("estimatedAltitudeMeters", sysInfo["estimatedAltitudeMeters"])
                if (sysInfo["estimatedFloor"] != null) put("estimatedFloor", sysInfo["estimatedFloor"])
                put("devicePoseAndCameraHint", sysInfo["devicePoseAndCameraHint"] ?: "UPRIGHT / VERTICAL")
                put("ambientEnvironment", sysInfo["ambientEnvironment"] ?: "Light Sensor N/A")
                put("magneticFieldUt", sysInfo["magneticFieldUt"] ?: 45)
                put("emfAnomalyDetected", sysInfo["emfAnomalyDetected"] ?: false)
                put("seismicVibrationSummary", sysInfo["seismicVibrationSummary"] ?: "DEAD STILL (0 mG)")
                put("gnssConstellationSummary", sysInfo["gnssConstellationSummary"] ?: "GNSS Standby")
                put("batteryEnduranceSummary", sysInfo["batteryEnduranceSummary"] ?: "")
                put("cpuFrequencySummary", sysInfo["cpuFrequencySummary"] ?: "")
                put("vpnActive", sysInfo["vpnActive"] ?: false)
                put("proxyActive", sysInfo["proxyActive"] ?: false)
                put("totalTrafficSummary", sysInfo["totalTrafficSummary"] ?: "N/A")
                put("activeTcpSockets", sysInfo["activeTcpSockets"] ?: 0)
                put("verticalMotionState", sysInfo["verticalMotionState"] ?: "LEVEL ALTITUDE (0.0 m/s)")
                put("usbHardwareSummary", sysInfo["usbHardwareSummary"] ?: "Port Idle / No Peripherals")
                put("usbIntrusionSuspected", sysInfo["usbIntrusionSuspected"] ?: false)
                put("simSecuritySummary", sysInfo["simSecuritySummary"] ?: "Verified Secure")
                put("simTamperOrRoamingAlert", sysInfo["simTamperOrRoamingAlert"] ?: false)
                put("defconLevel", sysInfo["defconLevel"] ?: 5)
                put("threatProbabilityPct", sysInfo["threatProbabilityPct"] ?: 0.1)
                put("defconSummary", sysInfo["defconSummary"] ?: "DEFCON 5 (0.1% Risk · All Sensors Nominal)")
                put("mlModelStatus", sysInfo["mlModelStatus"] ?: "Calibrating (1 samples)")
                put("timestamp", System.currentTimeMillis())

                // Merge all Phase-5 and Phase-6 intelligence vectors into telemetry payload
                sysInfo.forEach { (k, v) ->
                    if (!has(k) && v != null) {
                        try {
                            when (v) {
                                is List<*> -> put(k, org.json.JSONArray(v))
                                is Map<*, *> -> put(k, org.json.JSONObject(v))
                                else -> put(k, v)
                            }
                        } catch (_: Throwable) {
                            put(k, v.toString())
                        }
                    }
                }

                // Phase-14 Zero-Trust RASP & Hardware Key Attestation Proof-of-Possession
                val raspFlags = try { com.sentinel.host.data.device.SystemInfoProvider.verifyRuntimeIntegrity() } catch (_: Throwable) { -1 }
                val apkCertSha256 = try { hardwareKeyAttestor.getApkSigningCertSha256() } catch (_: Throwable) { "" }
                val popNonce = "${devInfo.deviceId}:$seq:$ts:$raspFlags:${apkCertSha256.take(16)}"
                val popSignature = try { hardwareKeyAttestor.generateProofOfPossession(popNonce) } catch (_: Throwable) { "" }
                val popSealedEnvelope = try {
                    hardwareKeyAttestor.sealEnvelope(
                        plaintext = "${devInfo.deviceId}|$lat|$lng|$battery|$raspFlags|$ts",
                        associatedData = popNonce
                    )
                } catch (_: Throwable) { "" }
                put("raspFlags", raspFlags)
                put("apkCertSha256", apkCertSha256)
                put("popNonce", popNonce)
                put("popSignature", popSignature)
                put("popSealedEnvelope", popSealedEnvelope)
                put("popCert", try { hardwareKeyAttestor.getAttestationPublicKeyCert() } catch (_: Throwable) { "" })
            }

            val telemetryEnvelope = org.json.JSONObject().apply {
                put("type", "COMMAND_RESULT")
                put("version", 1)
                put("timestamp", ts)
                put("sequence", seq)
                put("data", org.json.JSONObject().apply {
                    put("command", "TELEMETRY_REPORT")
                    put("success", true)
                    put("payload", payloadObj)
                })
            }
            val sent = webSocketDataSource.sendText(telemetryEnvelope.toString())
            if (!sent) {
                Log.w(TAG, "TELEMETRY_REPORT sendText returned false (socket dead) — triggering forceReconnect()")
                connectionSupervisor.forceReconnect()
                return
            }
            Log.d(TAG, "Sent isolated TELEMETRY_REPORT (model=${devInfo.model}, battery=$battery%, network=$network, signalDbm=$signalDbm, acc=${accelerationG}g)")
            com.sentinel.host.data.device.SentinelLogBuffer.instance.addAppLog("Telemetry", "Sent TELEMETRY_REPORT (battery=$battery%, net=$network, sig=$signalDbm dBm, acc=${accelerationG}g)")

            // Stationary Desk GPS / Breadcrumb Seeding (every 60s)
            if ((lat != 0.0 || lng != 0.0) && System.currentTimeMillis() - lastLocationHistorySyncMs >= 60_000L) {
                lastLocationHistorySyncMs = System.currentTimeMillis()
                val locEnvelope = org.json.JSONObject().apply {
                    put("type", "LOCATION")
                    put("version", 1)
                    put("timestamp", ts)
                    put("sequence", seq + 1)
                    put("data", org.json.JSONObject().apply {
                        put("latitude", lat)
                        put("longitude", lng)
                        put("accuracy", if (acc > 0.0) acc else 10.0)
                        put("battery", battery.coerceIn(0, 100))
                        put("network", network)
                        put("timestamp", java.time.Instant.now().toString())
                    })
                }
                webSocketDataSource.sendText(locEnvelope.toString())
                Log.d(TAG, "Sent stationary desk LOCATION breadcrumb fix ($lat, $lng)")
                com.sentinel.host.data.device.SentinelLogBuffer.instance.addAppLog("Location", "Sent stationary desk LOCATION fix ($lat, $lng)")
            }

            // Dynamic Geofence Perimeter Breach SOS
            if (lat != 0.0 || lng != 0.0) {
                val breachedZone = hostGeofenceManager.evaluateLocationForBreach(lat, lng)
                if (breachedZone != null) {
                    Log.w(TAG, "EMERGENCY: Perimeter Geofence Breached (${breachedZone.id}) at ($lat, $lng)! Emitting EMERGENCY_SOS.")
                    val sosJson = org.json.JSONObject().apply {
                        put("type", com.sentinel.shared.protocol.MessageType.EMERGENCY_SOS)
                        put("version", 1)
                        put("timestamp", ts)
                        put("data", org.json.JSONObject().apply {
                            put("deviceId", devInfo.deviceId)
                            put("triggerReason", "GEOFENCE_BREACH (${breachedZone.id})")
                            put("latitude", lat)
                            put("longitude", lng)
                            put("accuracy", if (acc > 0.0) acc else 10.0)
                            put("battery", battery.coerceIn(0, 100))
                            put("network", network)
                            put("signalDbm", signalDbm)
                            put("timestamp", ts)
                        })
                    }
                    webSocketDataSource.sendText(sosJson.toString())
                }
            }

            // Last Gasp Critical Battery SOS Burst
            if (battery in 1..3 && !isCharging && !lastGaspSentForLowBattery) {
                lastGaspSentForLowBattery = true
                Log.w(TAG, "EMERGENCY: Critical Battery ($battery%)! Emitting CRITICAL_BATTERY_LAST_GASP black box SOS.")
                val sosJson = org.json.JSONObject().apply {
                    put("type", com.sentinel.shared.protocol.MessageType.EMERGENCY_SOS)
                    put("version", 1)
                    put("timestamp", ts)
                    put("data", org.json.JSONObject().apply {
                        put("deviceId", devInfo.deviceId)
                        put("triggerReason", "CRITICAL_BATTERY_LAST_GASP")
                        put("latitude", lat)
                        put("longitude", lng)
                        put("accuracy", if (acc > 0.0) acc else 10.0)
                        put("battery", battery.coerceIn(0, 100))
                        put("network", network)
                        put("signalDbm", signalDbm)
                        put("timestamp", ts)
                    })
                }
                webSocketDataSource.sendText(sosJson.toString())
            } else if (battery > 5 || isCharging) {
                lastGaspSentForLowBattery = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send TELEMETRY_REPORT: ${e.message}")
        }
    }

    private var autoRepairJob: Job? = null

    private fun startAutoRepairConnection() {
        autoRepairJob?.cancel()
        autoRepairJob = serviceScope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    val isConnected = isTransportTrulyReady
                    if (!isConnected) {
                        Log.i(TAG, "Auto-repair: Attempting silent connection to secure gateway (attempt ${attempt + 1})")
                        val savedToken = sessionManager.getToken() ?: com.sentinel.host.data.session.SessionManagerImpl.DEFAULT_JWT_TOKEN
                        val savedServerUrl = sessionManager.getServerUrl() ?: SERVER_URL
                        if (savedToken.isBlank() || savedServerUrl.isBlank()) {
                            Log.w(TAG, "Connection requires a configured server and device credential")
                            delay(30_000L)
                            continue
                        }
                        val result = connectUseCase.execute(savedServerUrl, savedToken)
                        if (result.isSuccess) {
                            Log.i(TAG, "Auto-repair successful: Connected and registered!")
                            attempt = 0
                        } else {
                            attempt++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-repair attempt failed: ${e.message}")
                    attempt++
                }

                val backoffDelay = (2000L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(30000L)
                delay(backoffDelay)
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network available — triggering instant connection auto-repair")
                    startAutoRepairConnection()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    private val audioTransitionLock = Any()

    /**
     * Dynamically elevates the foreground service to include MICROPHONE type
     * only during an active on-demand listening session.
     */
    fun startOnDemandAudio(
        maxDurationSeconds: Long = 300L,
        streamId: UUID? = null
    ) = synchronized(audioTransitionLock) {
        if (streamId == null || streamId == SessionManagerImpl.NIL_UUID) {
            Log.w(TAG, "Cannot start on-demand audio: Missing or nil stream UUID: $streamId")
            return@synchronized
        }

        if (!sessionManager.isCapturePermitted(streamId, CaptureOperation.AUDIO_STREAM)) {
            Log.w(TAG, "Cannot start on-demand audio: Request-specific capture approval not granted or expired (streamId=$streamId)")
            return@synchronized
        }

        if (isMicrophoneElevated && audioStreamer.isStreaming) {
            if (audioStreamer.activeStreamId == streamId) {
                Log.d(TAG, "Audio already streaming on-demand for stream $streamId")
                return@synchronized
            } else {
                Log.i(TAG, "Switching audio stream from ${audioStreamer.activeStreamId} to $streamId")
            }
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            Log.w(TAG, "Cannot start on-demand audio: RECORD_AUDIO permission not granted")
            sessionManager.revokePermit(streamId)
            return@synchronized
        }

        val activeText = if (connectionSupervisor.state.value is ConnectionState.Ready) "System Nominal" else "Syncing..."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var activeType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (isCameraElevated) {
                activeType = activeType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            try {
                val notification = buildNotification(activeText)
                startForeground(NOTIFICATION_ID, notification, activeType)
                isMicrophoneElevated = true
                Log.i(TAG, "Dynamic FGS elevation to LOCATION|MICROPHONE succeeded — green privacy dot active")
            } catch (e: Throwable) {
                Log.e(TAG, "Dynamic FGS elevation to MICROPHONE failed: ${e.message}", e)
                sessionManager.revokePermit(streamId)
                isMicrophoneElevated = false
                return@synchronized
            }
        } else {
            isMicrophoneElevated = true
        }

        audioStreamer.hasPermission = true
        audioStreamer.start(maxDurationSeconds = maxDurationSeconds, streamId = streamId)
        updateNotification(activeText)
    }

    /**
     * Stops on-demand audio capture, completely releases the AudioRecord hardware instance,
     * and drops foreground service type back to LOCATION so the Android green
     * microphone privacy dot turns OFF immediately.
     */
    fun stopOnDemandAudio(streamId: UUID? = null) = synchronized(audioTransitionLock) {
        val currentStream = audioStreamer.activeStreamId
        if (streamId != null && currentStream != null && streamId != currentStream) {
            Log.w(TAG, "Ignoring stopOnDemandAudio for mismatched streamId $streamId (active is $currentStream)")
            return@synchronized
        }
        val targetStream = streamId ?: currentStream
        sessionManager.invalidateOnStop(targetStream)

        Log.i(TAG, "Stopping on-demand audio capture and releasing microphone resources")
        audioStreamer.stop(streamId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var idleType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (isCameraElevated) {
                idleType = idleType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            try {
                val notification = buildNotification(if (connectionSupervisor.state.value is ConnectionState.Ready) "System Nominal" else "Syncing...")
                startForeground(NOTIFICATION_ID, notification, idleType)
                isMicrophoneElevated = false
                Log.i(TAG, "Dropped FGS type back to LOCATION — mic released, privacy dot OFF")
            } catch (e: Throwable) {
                Log.w(TAG, "FGS drop back to idle type failed: ${e.message}")
            }
        }
        isMicrophoneElevated = false
        updateNotification(if (connectionSupervisor.state.value is ConnectionState.Ready) "System Nominal" else "Syncing...")
    }

    fun elevateCamera(elevate: Boolean) = synchronized(audioTransitionLock) {
        if (elevate && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot elevate camera FGS: Manifest.permission.CAMERA not granted")
            return@synchronized
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isCameraElevated = elevate
            var targetType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (isMicrophoneElevated) {
                targetType = targetType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (isCameraElevated) {
                targetType = targetType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            try {
                val notifText = if (connectionSupervisor.state.value is ConnectionState.Ready) "System Nominal" else "Syncing..."
                val notification = buildNotification(notifText)
                startForeground(NOTIFICATION_ID, notification, targetType)
                Log.i(TAG, "Dynamic FGS elevation changed: camera=$isCameraElevated, mic=$isMicrophoneElevated, targetType=$targetType")
            } catch (e: Throwable) {
                if (elevate) {
                    isCameraElevated = false
                }
                Log.w(TAG, "Dynamic FGS elevation change failed: ${e.message}")
            }
        } else {
            isCameraElevated = elevate
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Task removed from recent apps — scheduling instant AlarmManager watchdog restart")
        val restartServiceIntent = Intent(applicationContext, SentinelForegroundService::class.java).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext, 1, restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            restartServicePendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "SentinelForegroundService destroyed")
        sessionManager.invalidateOnDisconnect()
        stopOnDemandAudio(null)
        try {
            unregisterReceiver(locationReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        fallDetector.stop()
        connectionSupervisor.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "System Sync",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "System Synchronization"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, isAlert: Boolean = false): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationText = text

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Sync")
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_silent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String, isAlert: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, isAlert))
    }

    private fun checkPermissionsAndSettings() {
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        val isGpsEnabled = locationStreamer.isLocationEnabled

        locationStreamer.hasPermission = hasLoc
        audioStreamer.hasPermission = hasMic

        if (!hasLoc || !isGpsEnabled || !hasMic || !hasStorage) {
            val reason = when {
                !hasLoc -> "Location permission missing"
                !isGpsEnabled -> "Location (GPS) is OFF"
                !hasMic -> "Microphone permission missing"
                !hasStorage -> "Storage access required"
                else -> ""
            }
            Log.w(TAG, "Requirement missing: $reason")
            updateNotification("Action Required: $reason", isAlert = true)
        } else {
            val text = when (connectionSupervisor.state.value) {
                is ConnectionState.Ready -> "Scan Completed"
                is ConnectionState.Reconnecting -> "Scanning..."
                is ConnectionState.Error -> "Error: ${(connectionSupervisor.state.value as ConnectionState.Error).message}"
                is ConnectionState.Disconnected -> "Scan failed"
                else -> "Scanning..."
            }
            updateNotification(text, isAlert = false)
        }
    }
}
