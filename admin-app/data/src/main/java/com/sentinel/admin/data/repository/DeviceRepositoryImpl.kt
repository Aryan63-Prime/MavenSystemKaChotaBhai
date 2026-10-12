package com.sentinel.admin.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.sentinel.admin.data.remote.api.DeviceApi
import com.sentinel.admin.data.remote.api.DeviceMapper.toDomain
import com.sentinel.admin.data.remote.protocol.DeviceUpdateDataJson
import com.sentinel.admin.data.remote.protocol.DeviceUpdateEventMapper
import com.sentinel.admin.data.remote.protocol.DeviceUpdateMessageJson
import com.sentinel.admin.domain.model.ConnectionEvent
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.domain.model.DeviceUpdateEvent
import com.sentinel.admin.domain.model.EventStatistics
import com.sentinel.admin.domain.repository.AuthRepository
import com.sentinel.admin.domain.repository.ConnectionRepository
import com.sentinel.admin.domain.repository.DeviceRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException

/**
 * REST + WebSocket hybrid implementation of [DeviceRepository].
 *
 * - REST provides the initial device snapshot via [getDevices]/[getDevice]
 * - WebSocket DEVICE_UPDATE events apply incremental O(1) patches
 * - Maintains an immutable [Map] keyed by deviceId / uniqueKey
 * - Tracks per-device sequence numbers for ordering
 * - Suppresses duplicate and stale events
 * - Provides persistent local storage cache to avoid UI flickering or lost telemetry across refreshes
 */
class DeviceRepositoryImpl(
    private val deviceApi: DeviceApi,
    private val authRepository: AuthRepository,
    private val connectionRepository: ConnectionRepository,
    private val eventMapper: DeviceUpdateEventMapper,
    private val moshi: Moshi,
    scope: CoroutineScope,
    private val context: Context? = null,
    private val receiptClock: Clock = Clock.systemUTC()
) : DeviceRepository {

    companion object {
        private const val TAG = "Sentinel:DeviceRepo"
    }

    // ============================================================
    // State
    // ============================================================

    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()

    private val _deviceUpdates = MutableSharedFlow<DeviceUpdateEvent>(extraBufferCapacity = 64)
    override val deviceUpdates: SharedFlow<DeviceUpdateEvent> = _deviceUpdates.asSharedFlow()

    private val _eventStatistics = MutableStateFlow(EventStatistics())
    override val eventStatistics: StateFlow<EventStatistics> = _eventStatistics.asStateFlow()

    private val _emergencyAlert = MutableStateFlow<com.sentinel.admin.domain.model.EmergencyAlert?>(null)
    override val emergencyAlert: StateFlow<com.sentinel.admin.domain.model.EmergencyAlert?> = _emergencyAlert.asStateFlow()

    private val notificationManager: com.sentinel.admin.data.notification.EmergencyNotificationManager? by lazy {
        context?.let { com.sentinel.admin.data.notification.EmergencyNotificationManager(it) }
    }

    override fun dismissEmergencyAlert() {
        notificationManager?.silenceAlarm()
        _emergencyAlert.value = null
    }

    override fun triggerTestEmergencyAlert(alert: com.sentinel.admin.domain.model.EmergencyAlert) {
        _emergencyAlert.value = alert
        notificationManager?.triggerEmergencyAlert(alert)
    }

    /** Per-device last sequence for ordering. */
    private val lastSequence = mutableMapOf<String, Long>()

    /** Per-device monotonic location revision to reconcile REST responses with in-flight live updates. */
    private val locationRevisions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun incrementLocationRevision(deviceKey: String): Long {
        return locationRevisions.compute(deviceKey) { _, v -> (v ?: 0L) + 1L } ?: 1L
    }

    private fun getLocationRevision(deviceKey: String): Long {
        return locationRevisions[deviceKey] ?: 0L
    }

    /** Track devices that report dedicated telemetry so generic server broadcasts don't clobber them. */
    private val dedicatedTelemetryDevices = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    data class DedicatedSnapshot(
        val deviceKey: String,
        val latitude: Double,
        val longitude: Double,
        val battery: Int,
        val network: String,
        val timestamp: Long
    )

    private val recentDedicatedSnapshots = java.util.concurrent.ConcurrentLinkedQueue<DedicatedSnapshot>()

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences("sentinel_device_cache", Context.MODE_PRIVATE)
    }

    private val inMemoryCallsignMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun loadCallsignMapping() {
        val jsonStr = prefs?.getString("sentinel_callsign_registry", null) ?: return
        try {
            val obj = JSONObject(jsonStr)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                inMemoryCallsignMap[k] = obj.getString(k)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load callsign registry: ${e.message}")
        }
    }

    private fun saveCallsignMapping() {
        try {
            val obj = JSONObject()
            for ((k, v) in inMemoryCallsignMap) {
                obj.put(k, v)
            }
            prefs?.edit()?.putString("sentinel_callsign_registry", obj.toString())?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save callsign registry: ${e.message}")
        }
    }

    @Synchronized
    private fun getOrCreateCallsign(uniqueKey: String, model: String, preferredCallsign: String = ""): String {
        if (inMemoryCallsignMap.isEmpty()) {
            loadCallsignMapping()
        }

        // 1. If explicit non-blank callsign is provided, record and return
        if (preferredCallsign.isNotBlank()) {
            inMemoryCallsignMap[uniqueKey] = preferredCallsign
            saveCallsignMapping()
            return preferredCallsign
        }

        // 2. Direct match on uniqueKey
        inMemoryCallsignMap[uniqueKey]?.let { return it }

        // 4. Pre-seed default fleet callsigns for known devices if not yet assigned
        if (model.contains("CPH2569", ignoreCase = true) && !inMemoryCallsignMap.values.contains("HOST-01")) {
            val cs = "HOST-01"
            inMemoryCallsignMap[uniqueKey] = cs
            saveCallsignMapping()
            return cs
        }
        if (model.contains("I2401", ignoreCase = true) && !inMemoryCallsignMap.values.contains("HOST-02")) {
            val cs = "HOST-02"
            inMemoryCallsignMap[uniqueKey] = cs
            saveCallsignMapping()
            return cs
        }

        // 5. Sequential assignment for any new device (HOST-01, HOST-02, HOST-03...)
        val existingNumbers = inMemoryCallsignMap.values.mapNotNull { cs ->
            val match = Regex("""HOST-(\d+)""").find(cs)
            match?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()

        var nextNum = 1
        while (existingNumbers.contains(nextNum)) {
            nextNum++
        }

        val newCallsign = String.format("HOST-%02d", nextNum)
        inMemoryCallsignMap[uniqueKey] = newCallsign
        saveCallsignMapping()
        return newCallsign
    }

    private fun serializeDevices(devices: Collection<Device>): String {
        return try {
            val arr = JSONArray()
            for (d in devices.distinctBy { it.uniqueKey }) {
                val obj = JSONObject().apply {
                    put("deviceId", d.deviceId)
                    put("connectionId", d.connectionId)
                    put("authenticated", d.authenticated)
                    put("registered", d.registered)
                    put("registrationState", d.registrationState)
                    put("heartbeatStatus", d.heartbeatStatus)
                    put("connectedAt", d.connectedAt)
                    put("lastHeartbeat", d.lastHeartbeat)
                    put("deviceName", d.deviceName)
                    put("appVersion", d.appVersion)
                    put("model", d.model)
                    put("callsign", d.callsign)
                    put("hardwareId", d.hardwareId)
                    val loc = d.latestLocation
                    if (loc != null) {
                        val locObj = JSONObject().apply {
                            put("deviceId", loc.deviceId)
                            put("latitude", loc.latitude)
                            put("longitude", loc.longitude)
                            put("accuracy", loc.accuracy)
                            put("battery", loc.battery)
                            put("network", loc.network)
                            put("recordedAt", loc.recordedAt)
                            put("hasReportedFix", loc.hasReportedFix)
                            if (loc.receivedAt != null) put("receivedAt", loc.receivedAt)
                        }
                        put("latestLocation", locObj)
                    }
                }
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Error serializing device cache: ${e.message}")
            ""
        }
    }

    private fun deserializeDevices(jsonStr: String): List<Device> {
        val list = mutableListOf<Device>()
        if (jsonStr.isBlank()) return list
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val locObj = obj.optJSONObject("latestLocation")
                val loc = if (locObj != null) {
                    val lat = locObj.optDouble("latitude", 0.0)
                    val lon = locObj.optDouble("longitude", 0.0)
                    val acc = locObj.optDouble("accuracy", 0.0)
                    val hasFix = if (locObj.has("hasReportedFix")) {
                        locObj.optBoolean("hasReportedFix", true)
                    } else {
                        coordinatesAreValid(lat, lon) && (acc > 0.0 || locObj.has("accuracy"))
                    }
                    DeviceLocation(
                        deviceId = locObj.optString("deviceId", obj.getString("deviceId")),
                        latitude = lat,
                        longitude = lon,
                        accuracy = acc,
                        battery = locObj.optInt("battery", -1),
                        network = locObj.optString("network", "unknown"),
                        recordedAt = locObj.optString("recordedAt", ""),
                        hasReportedFix = hasFix,
                        receivedAt = if (locObj.has("receivedAt")) locObj.optString("receivedAt") else null
                    )
                } else null

                val model = obj.optString("model", "")
                val deviceId = obj.getString("deviceId")
                val connectionId = obj.optString("connectionId", "")
                val uniqueKey = when {
                    deviceId.isNotBlank() && deviceId != "HOST-001" && (deviceId.startsWith("HOST-") || deviceId.contains("-")) -> deviceId
                    model.isNotBlank() && model != "Unknown" -> "${deviceId}_${model}"
                    connectionId.isNotBlank() -> "${deviceId}_${connectionId}"
                    else -> deviceId
                }
                val rawCallsign = obj.optString("callsign", "")
                val callsign = getOrCreateCallsign(uniqueKey, model, preferredCallsign = rawCallsign)

                list.add(
                    Device(
                        deviceId = deviceId,
                        connectionId = connectionId,
                        authenticated = obj.optBoolean("authenticated", true),
                        registered = obj.optBoolean("registered", true),
                        registrationState = obj.optString("registrationState", "registered"),
                        heartbeatStatus = obj.optString("heartbeatStatus", "offline"),
                        connectedAt = obj.optString("connectedAt", ""),
                        lastHeartbeat = obj.optString("lastHeartbeat", ""),
                        deviceName = obj.optString("deviceName", ""),
                        appVersion = obj.optString("appVersion", ""),
                        model = model,
                        latestLocation = loc,
                        callsign = callsign,
                        hardwareId = obj.optString("hardwareId", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize cached devices: ${e.message}")
        }
        return list
    }

    private fun saveDeviceCache(devices: Map<String, Device>) {
        try {
            val serialized = serializeDevices(devices.values)
            if (serialized.isNotBlank()) {
                prefs?.edit()?.putString("cached_devices", serialized)?.apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save device cache: ${e.message}")
        }
    }

    private fun loadDeviceCache(): Map<String, Device> {
        val saved = prefs?.getString("cached_devices", null) ?: return emptyMap()
        val list = deserializeDevices(saved)
        val map = LinkedHashMap<String, Device>()
        for (d in list) {
            map[d.uniqueKey] = d
            if (d.connectionId.isNotBlank()) map[d.connectionId] = d
            if (!map.containsKey(d.deviceId)) map[d.deviceId] = d
        }
        return map
    }

    private fun isEchoOfDedicatedTelemetry(lat: Double, lng: Double, battery: Int?): Boolean {
        val now = System.currentTimeMillis()
        recentDedicatedSnapshots.removeIf { now - it.timestamp > 120_000L }

        return recentDedicatedSnapshots.any { snap ->
            val hasCoords = (lat != 0.0 && lng != 0.0 && snap.latitude != 0.0 && snap.longitude != 0.0)
            val coordsMatch = hasCoords && (Math.abs(snap.latitude - lat) < 0.0001 && Math.abs(snap.longitude - lng) < 0.0001)
            val hasBattery = (battery != null && battery >= 0 && snap.battery >= 0)
            val batteryMatch = hasBattery && (battery == snap.battery)

            if (hasBattery && !batteryMatch) {
                // Different battery percentage -> definitely NOT an echo of this snapshot
                false
            } else if (coordsMatch && batteryMatch) {
                true
            } else if (coordsMatch && !hasBattery) {
                true
            } else if (batteryMatch && !hasCoords) {
                true
            } else {
                false
            }
        }
    }

    private fun isEchoOfDedicatedBattery(battery: Int): Boolean {
        val now = System.currentTimeMillis()
        recentDedicatedSnapshots.removeIf { now - it.timestamp > 120_000L }
        return recentDedicatedSnapshots.any { it.battery == battery }
    }

    private fun isEchoOfDedicatedNetwork(network: String): Boolean {
        val now = System.currentTimeMillis()
        recentDedicatedSnapshots.removeIf { now - it.timestamp > 120_000L }
        return recentDedicatedSnapshots.any { it.network.equals(network, ignoreCase = true) }
    }

    private fun queryDevicesSystemInfo() {
        try {
            val ts = System.currentTimeMillis() / 1000
            val seq = System.currentTimeMillis()
            val cmdJson = """{"type":"COMMAND","version":1,"timestamp":$ts,"sequence":$seq,"data":{"targetDeviceId":"HOST-001","command":"GET_SYSTEM_INFO","params":{}}}"""
            connectionRepository.sendText(cmdJson)

            // Also send targeted requests for all distinct known devices
            val knownKeys = _devices.value.values.map { it.uniqueKey }.distinct()
            for (key in knownKeys) {
                if (key != "HOST-001") {
                    val targetedJson = """{"type":"COMMAND","version":1,"timestamp":$ts,"sequence":$seq,"data":{"targetDeviceId":"$key","command":"GET_SYSTEM_INFO","params":{"targetUniqueKey":"$key"}}}"""
                    connectionRepository.sendText(targetedJson)
                }
            }
        } catch (_: Exception) {}
    }

    private val deviceUpdateMessageAdapter by lazy {
        moshi.adapter(DeviceUpdateMessageJson::class.java)
    }

    // ============================================================
    // Init — subscribe to WebSocket events
    // ============================================================

    init {
        val cached = loadDeviceCache()
        if (cached.isNotEmpty()) {
            _devices.value = cached
            Log.i(TAG, "Initialized DeviceRepository with ${cached.values.distinctBy { it.uniqueKey }.size} cached devices: ${cached.values.distinctBy { it.uniqueKey }.map { "${it.model}(${it.latestLocation?.battery}%)" }}")
        }

        connectionRepository.events
            .onEach { event ->
                when (event) {
                    is ConnectionEvent.DeviceUpdateReceived -> handleDeviceUpdate(event.rawJson)
                    is ConnectionEvent.CommandResultReceived -> handleCommandResult(event.rawJson)
                    else -> { /* Not our concern */ }
                }
            }
            .launchIn(scope)

        connectionRepository.state
            .onEach { state ->
                if (state is ConnectionState.Ready) {
                    queryDevicesSystemInfo()
                }
            }
            .launchIn(scope)
    }

    // ============================================================
    // REST methods (unchanged behavior)
    // ============================================================

    override suspend fun getDevices(): Result<List<Device>> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(IllegalStateException("Not authenticated"))

            val response = deviceApi.getDevices("Bearer $token")
            val deviceList = response.devices.map { it.toDomain() }
                .filter { it.registered && it.deviceId.isNotBlank() }

            // Populate live map from REST snapshot keyed by unique device key (model/connectionId)
            val currentMap = _devices.value
            val deviceMap = LinkedHashMap<String, Device>(currentMap)

            // Reconcile with server: mark devices not returned by the server as offline
            val returnedKeys = deviceList.map { it.uniqueKey }.toSet()
            val returnedConnectionIds = deviceList.map { it.connectionId }.filter { it.isNotBlank() }.toSet()

            for ((key, cachedDev) in currentMap) {
                val isReturned = returnedKeys.contains(cachedDev.uniqueKey) ||
                        returnedConnectionIds.contains(cachedDev.connectionId)
                if (!isReturned && cachedDev.heartbeatStatus == "online") {
                    val offlineDev = cachedDev.copy(heartbeatStatus = "offline")
                    deviceMap[key] = offlineDev
                    if (offlineDev.uniqueKey != key) deviceMap[offlineDev.uniqueKey] = offlineDev
                    if (offlineDev.connectionId.isNotBlank()) deviceMap[offlineDev.connectionId] = offlineDev
                }
            }

            for (dev in deviceList) {
                val existing = currentMap[dev.uniqueKey]
                    ?: currentMap[dev.connectionId]
                    ?: currentMap.values.find {
                        dev.deviceId == "HOST-001" && it.deviceId.startsWith("HOST-001-") &&
                            it.model.equals(dev.model, ignoreCase = true) && it.model.isNotBlank()
                    }
                    ?: if (dev.callsign.isNotBlank()) currentMap.values.find { it.resolvedCallsign.equals(dev.callsign, ignoreCase = true) } else null

                val serverLoc = dev.latestLocation
                val isEchoOfAnotherDevice = dev.deviceId == "HOST-001" && serverLoc != null && recentDedicatedSnapshots.any { snap ->
                    snap.deviceKey != dev.uniqueKey &&
                    snap.battery >= 0 && snap.battery == serverLoc.battery &&
                    (serverLoc.latitude == 0.0 || Math.abs(snap.latitude - serverLoc.latitude) < 0.0001)
                }

                val safeServerLoc = if (isEchoOfAnotherDevice) null else serverLoc
                val resolvedLocation = reconcileLocation(dev.uniqueKey, existing?.latestLocation, safeServerLoc)
                val hwCallsign = Regex("""\((HOST-[A-Za-z0-9_-]+)\)""").find(dev.deviceName)?.groupValues?.get(1) ?: ""
                val callsign = hwCallsign.ifBlank {
                    existing?.callsign?.ifBlank { null }
                        ?: getOrCreateCallsign(dev.uniqueKey, dev.model)
                }

                val isStaleOrOffline = dev.heartbeatStatus.equals("stale", ignoreCase = true) ||
                        dev.heartbeatStatus.equals("offline", ignoreCase = true)
                val status = if (existing?.heartbeatStatus == "online") "online" else if (isStaleOrOffline) "offline" else "online"

                val targetKey = existing?.uniqueKey ?: dev.uniqueKey
                val merged = dev.copy(
                    deviceId = if (existing != null && existing.deviceId.startsWith("HOST-") && existing.deviceId != "HOST-001") existing.deviceId else dev.deviceId,
                    latestLocation = resolvedLocation,
                    heartbeatStatus = status,
                    callsign = callsign
                )

                deviceMap[targetKey] = merged
                if (targetKey != merged.uniqueKey) {
                    deviceMap[merged.uniqueKey] = merged
                }
                if (merged.connectionId.isNotBlank()) {
                    deviceMap[merged.connectionId] = merged
                }
                if (!deviceMap.containsKey(merged.deviceId) || deviceMap[merged.deviceId]?.heartbeatStatus != "online") {
                    deviceMap[merged.deviceId] = merged
                }
            }
            _devices.value = deviceMap
            saveDeviceCache(deviceMap)
            // Reset sequence tracking on full refresh
            lastSequence.clear()
            _eventStatistics.update { it.copy(reconnectResyncs = it.reconnectResyncs + 1) }

            queryDevicesSystemInfo()

            Result.success(deviceMap.values.distinctBy { it.uniqueKey })
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Result.failure(mapHttpError(e))
        } catch (e: IOException) {
            Result.failure(IOException("Network error: ${e.message}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Validates that the returned device identity matches the requested and cached identity
     * before ANY cache, alias, callsign, or persistence mutation occurs.
     */
    private fun validateDeviceResponseIdentity(
        requestedId: String,
        device: Device,
        cached: Device?
    ): Result<Unit> {
        if (cached != null) {
            if (device.deviceId != cached.deviceId ||
                (device.model.isNotBlank() && cached.model.isNotBlank() &&
                 !device.model.equals(cached.model, ignoreCase = true))
            ) {
                return Result.failure(
                    IllegalStateException("Device response does not match the requested identity: expected ${cached.deviceId} (${cached.model}), got ${device.deviceId} (${device.model})")
                )
            }
        } else {
            val matchesRequested = device.deviceId == requestedId ||
                    device.uniqueKey == requestedId ||
                    (device.connectionId.isNotBlank() && device.connectionId == requestedId)
            if (!matchesRequested) {
                return Result.failure(
                    IllegalStateException("Device response does not match the requested identity: expected $requestedId, got ${device.deviceId} / ${device.uniqueKey}")
                )
            }
        }
        return Result.success(Unit)
    }

    /**
     * Reconciles a fresh server location with an existing cached location, respecting:
     * - Fix presence (a valid REST fix replaces a synthetic no-fix cache entry, while an existing genuine fix is preserved if REST has no fix)
     * - In-flight live updates (if a live update arrived while the REST request was in-flight, live fix wins)
     * - Normalized sample timestamps (newer sample timestamp wins)
     * - Nested DeviceID identity checks
     */
    private fun reconcileLocation(
        targetKey: String,
        existingLoc: DeviceLocation?,
        serverLoc: DeviceLocation?,
        requestStartRevision: Long? = null
    ): DeviceLocation? {
        if (serverLoc == null || !coordinatesAreValid(serverLoc.latitude, serverLoc.longitude)) {
            return existingLoc
        }
        // Nested DeviceID protection: foreign device's location must not be merged
        if (serverLoc.deviceId.isNotBlank() &&
            serverLoc.deviceId != "HOST-001" &&
            !targetKey.contains(serverLoc.deviceId) &&
            !serverLoc.deviceId.contains(targetKey.substringBefore("_"))
        ) {
            return existingLoc
        }

        if (existingLoc == null) {
            return serverLoc
        }

        // 1. Existing has no reported fix (e.g. synthetic from battery/network), but server has reported fix:
        // Fresh REST fix wins!
        if (!existingLoc.hasReportedFix && serverLoc.hasReportedFix) {
            return serverLoc.copy(
                battery = if (existingLoc.battery >= 0) existingLoc.battery else serverLoc.battery,
                network = if (existingLoc.network.isNotBlank() && existingLoc.network != "unknown") existingLoc.network else serverLoc.network
            )
        }

        // 2. Existing has genuine reported fix, but server has NO reported fix:
        // Do not regress genuine fix to no-fix!
        if (existingLoc.hasReportedFix && !serverLoc.hasReportedFix) {
            return existingLoc.copy(
                battery = if (serverLoc.battery >= 0) serverLoc.battery else existingLoc.battery,
                network = if (serverLoc.network.isNotBlank() && serverLoc.network != "unknown") serverLoc.network else existingLoc.network
            )
        }

        // 3. Both have reported fixes:
        val currentRev = getLocationRevision(targetKey)
        val liveArrivedWhileInFlight = requestStartRevision != null && currentRev > requestStartRevision

        if (liveArrivedWhileInFlight) {
            val serverSample = normalizedSampleIso(serverLoc.recordedAt)
            val existingSample = normalizedSampleIso(existingLoc.recordedAt)
            if (serverSample != null && existingSample != null && serverSample > existingSample) {
                return serverLoc
            }
            return existingLoc
        }

        val serverSample = normalizedSampleIso(serverLoc.recordedAt)
        val existingSample = normalizedSampleIso(existingLoc.recordedAt)
        return when {
            serverSample != null && existingSample != null -> {
                if (serverSample >= existingSample) serverLoc else existingLoc
            }
            serverSample != null && existingSample == null -> serverLoc
            serverSample == null && existingSample != null -> existingLoc
            else -> serverLoc // When timestamps unknown on both, fresh REST fix replaces older cache
        }
    }

    /**
     * Merges a validated fresh device snapshot into the cache and updates persistence.
     */
    private fun mergeAndCacheDevice(
        device: Device,
        cached: Device?,
        requestedId: String,
        requestStartRevision: Long? = null
    ): Device {
        val targetKey = cached?.uniqueKey ?: device.uniqueKey

        val serverLoc = device.latestLocation
        val isEchoOfAnotherDevice = device.deviceId == "HOST-001" && serverLoc != null && recentDedicatedSnapshots.any { snap ->
            snap.deviceKey != targetKey &&
            ((snap.battery >= 0 && snap.battery == serverLoc.battery) ||
             (serverLoc.latitude != 0.0 && Math.abs(snap.latitude - serverLoc.latitude) < 0.0005 && Math.abs(snap.longitude - serverLoc.longitude) < 0.0005))
        }
        val safeServerLoc = if (isEchoOfAnotherDevice) null else serverLoc

        _devices.update { current ->
            val existing = current[targetKey] ?: current[requestedId]
            val callsign = existing?.callsign?.ifBlank { null }
                ?: getOrCreateCallsign(device.uniqueKey, device.model)
            val resolvedLocation = reconcileLocation(targetKey, existing?.latestLocation, safeServerLoc, requestStartRevision)
            val merged = if (existing != null && existing.registered && !device.registered) {
                existing.copy(
                    connectionId = device.connectionId.ifBlank { existing.connectionId },
                    heartbeatStatus = device.heartbeatStatus,
                    lastHeartbeat = if (device.lastHeartbeat.isNotBlank()) device.lastHeartbeat else existing.lastHeartbeat,
                    latestLocation = resolvedLocation,
                    callsign = callsign
                )
            } else if (existing != null) {
                existing.copy(
                    connectionId = device.connectionId.ifBlank { existing.connectionId },
                    heartbeatStatus = device.heartbeatStatus,
                    lastHeartbeat = if (device.lastHeartbeat.isNotBlank()) device.lastHeartbeat else existing.lastHeartbeat,
                    latestLocation = resolvedLocation,
                    deviceName = if (device.deviceName.isNotBlank()) device.deviceName else existing.deviceName,
                    model = if (device.model.isNotBlank()) device.model else existing.model,
                    callsign = callsign
                )
            } else {
                device.copy(latestLocation = resolvedLocation, callsign = callsign)
            }
            val updated = current.toMutableMap()
            if (existing != null) updated.entries.removeAll { it.value.uniqueKey == existing.uniqueKey }
            updated[targetKey] = merged
            if (merged.connectionId.isNotBlank()) updated[merged.connectionId] = merged
            if (!updated.containsKey(merged.deviceId)) updated[merged.deviceId] = merged
            saveDeviceCache(updated)
            updated
        }

        return _devices.value[targetKey] ?: cached ?: device
    }

    override suspend fun getDevice(deviceId: String): Result<Device> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(IllegalStateException("Not authenticated"))

            val cached = _devices.value[deviceId]
                ?: _devices.value.values.find { it.uniqueKey == deviceId || it.connectionId == deviceId || it.deviceId == deviceId }

            val serverDeviceId = cached?.uniqueKey ?: deviceId
            val requestRevision = getLocationRevision(serverDeviceId)
            val dto = try {
                deviceApi.getDevice("Bearer $token", serverDeviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cached != null) return Result.success(cached)
                throw e
            }
            val device = dto.toDomain()

            val validation = validateDeviceResponseIdentity(deviceId, device, cached)
            if (validation.isFailure) {
                if (cached != null) {
                    // Ordinary display fallback: preserve cached device without clobbering
                    return Result.success(cached)
                }
                return Result.failure(validation.exceptionOrNull() ?: IllegalStateException("Device response does not match the requested identity"))
            }

            val merged = mergeAndCacheDevice(device, cached, deviceId, requestRevision)
            Result.success(merged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Result.failure(mapHttpError(e))
        } catch (e: IOException) {
            Result.failure(IOException("Network error: ${e.message}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchFreshDevice(deviceId: String): Result<Device> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(IllegalStateException("Not authenticated"))

            val cached = _devices.value[deviceId]
                ?: _devices.value.values.find { it.uniqueKey == deviceId || it.connectionId == deviceId || it.deviceId == deviceId }

            val serverDeviceId = cached?.uniqueKey ?: deviceId
            val requestRevision = getLocationRevision(serverDeviceId)
            val dto = deviceApi.getDevice("Bearer $token", serverDeviceId)
            val device = dto.toDomain()

            val validation = validateDeviceResponseIdentity(deviceId, device, cached)
            if (validation.isFailure) {
                // Explicit refresh MUST fail on mismatched identity without mutating cache or falling back to cached
                return Result.failure(validation.exceptionOrNull() ?: IllegalStateException("Device response does not match the requested identity"))
            }

            val merged = mergeAndCacheDevice(device, cached, deviceId, requestRevision)
            Result.success(merged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Result.failure(mapHttpError(e))
        } catch (e: IOException) {
            Result.failure(IOException("Network error: ${e.message}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun wakeDevice(deviceId: String): Result<Boolean> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(IllegalStateException("Not authenticated"))

            val serverDeviceId = _devices.value[deviceId]?.deviceId ?: deviceId
            val response = deviceApi.wakeDevice("Bearer $token", serverDeviceId)
            if (response.success) {
                Log.i(TAG, "Successfully sent high-priority FCM wakeup to $deviceId (msgId=${response.messageId})")
                Result.success(true)
            } else {
                Result.failure(Exception("FCM wake ping failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching FCM wakeup to $deviceId: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ============================================================
    // WebSocket event handling
    // ============================================================

    private fun handleDeviceUpdate(rawJson: String) {
        _eventStatistics.update { it.copy(received = it.received + 1) }

        val message = try {
            deviceUpdateMessageAdapter.fromJson(rawJson)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed DEVICE_UPDATE payload, ignoring", e)
            _eventStatistics.update { it.copy(ignored = it.ignored + 1) }
            return
        }

        if (message == null) {
            _eventStatistics.update { it.copy(ignored = it.ignored + 1) }
            return
        }

        val sequence = message.sequence
        val deviceId = message.data.deviceId

        // Sequence ordering — reject stale/duplicate
        if (deviceId.isNotBlank()) {
            val sequenceKey = message.data.model?.takeIf { it.isNotBlank() }?.let { "${deviceId}_$it" } ?: deviceId
            val lastSeq = lastSequence[sequenceKey] ?: -1
            if (sequence > 0 && lastSeq >= 0) {
                when {
                    sequence == lastSeq -> {
                        _eventStatistics.update { it.copy(duplicates = it.duplicates + 1) }
                        return
                    }
                    sequence < lastSeq -> {
                        _eventStatistics.update { it.copy(stale = it.stale + 1) }
                        return
                    }
                }
            }
            if (sequence > 0) {
                lastSequence[sequenceKey] = sequence
            }
        }

        // Map to domain event
        val domainEvent = eventMapper.map(message.data)
        if (domainEvent == null) {
            _eventStatistics.update { it.copy(ignored = it.ignored + 1) }
            return
        }

        // Apply incremental patch
        applyEvent(domainEvent)
        _eventStatistics.update { it.copy(applied = it.applied + 1) }
        _deviceUpdates.tryEmit(domainEvent)
    }

    /**
     * Applies a domain event as an O(1) patch to the device map.
     * Disconnected devices are marked offline (not removed).
     */
    private fun applyEvent(event: DeviceUpdateEvent) {
        when (event) {
            is DeviceUpdateEvent.DeviceConnected -> {
                _devices.update { current ->
                    val hwCallsign = Regex("""\((HOST-[A-Za-z0-9_-]+)\)""").find(event.deviceName ?: "")?.groupValues?.get(1) ?: ""
                    val key = if (!event.model.isNullOrBlank()) {
                        "${event.deviceId}_${event.model}"
                    } else if (hwCallsign.isNotBlank()) {
                        "${event.deviceId}_$hwCallsign"
                    } else {
                        current.values.find { it.deviceId == event.deviceId }?.uniqueKey ?: event.deviceId
                    }
                    val existing = current[key] ?: (if (hwCallsign.isNotBlank()) current["${event.deviceId}_$hwCallsign"] else null) ?: current[event.deviceId]?.takeIf { event.model.isNullOrBlank() || it.model == event.model }
                    val resolvedModel = event.model?.takeIf { it.isNotBlank() } ?: existing?.model ?: ""
                    val callsign = hwCallsign.ifBlank {
                        existing?.callsign?.ifBlank { null } ?: getOrCreateCallsign(key, resolvedModel)
                    }
                    val patched = existing?.copy(
                        heartbeatStatus = "online",
                        registered = true,
                        registrationState = "registered",
                        deviceName = event.deviceName?.takeIf { it.isNotBlank() } ?: existing.deviceName,
                        appVersion = event.appVersion?.takeIf { it.isNotBlank() } ?: existing.appVersion,
                        model = resolvedModel,
                        callsign = callsign
                    ) ?: createMinimalDevice(event)

                    val updated = current.toMutableMap()
                    updated[patched.uniqueKey] = patched
                    if (key != patched.uniqueKey) updated[key] = patched
                    if (hwCallsign.isNotBlank()) updated["${patched.deviceId}_$hwCallsign"] = patched
                    if (patched.connectionId.isNotBlank()) updated[patched.connectionId] = patched
                    val distinct = updated.values.filter { it.deviceId == patched.deviceId }.distinctBy { it.uniqueKey }
                    if (distinct.size <= 1 || updated[patched.deviceId]?.uniqueKey == patched.uniqueKey) {
                        updated[patched.deviceId] = patched
                    }
                    saveDeviceCache(updated)
                    updated
                }
            }

            is DeviceUpdateEvent.DeviceDisconnected -> {
                _devices.update { current ->
                    val eventModel = event.model
                    if (!eventModel.isNullOrBlank()) {
                        val matchingTarget = current.values.find {
                            it.deviceId == event.deviceId && it.model.equals(eventModel, ignoreCase = true)
                        } ?: current.values.find { it.uniqueKey == "${event.deviceId}_$eventModel" }

                        if (matchingTarget != null) {
                            val patched = matchingTarget.copy(heartbeatStatus = "offline")
                            val updated = current.toMutableMap()
                            updated[patched.uniqueKey] = patched
                            if (patched.connectionId.isNotBlank()) updated[patched.connectionId] = patched
                            val distinct = updated.values.filter { it.deviceId == patched.deviceId }.distinctBy { it.uniqueKey }
                            if (distinct.size <= 1 || updated[patched.deviceId]?.uniqueKey == patched.uniqueKey) {
                                updated[patched.deviceId] = patched
                            }
                            saveDeviceCache(updated)
                            return@update updated
                        }
                    }

                    val distinctDevices = current.values.filter { it.deviceId == event.deviceId }.distinctBy { it.uniqueKey }
                    if (distinctDevices.size <= 1) {
                        val existing = current[event.deviceId] ?: current.values.firstOrNull { it.deviceId == event.deviceId } ?: return@update current
                        val patched = existing.copy(heartbeatStatus = "offline")
                        val updated = current.toMutableMap()
                        updated[patched.uniqueKey] = patched
                        if (patched.connectionId.isNotBlank()) updated[patched.connectionId] = patched
                        updated[event.deviceId] = patched
                        saveDeviceCache(updated)
                        updated
                    } else {
                        // When multiple devices share deviceId and model is absent, do not mark both offline prematurely.
                        current
                    }
                }
            }

            is DeviceUpdateEvent.HeartbeatReceived -> {
                _devices.update { current ->
                    val eventModel = event.model
                    if (!eventModel.isNullOrBlank()) {
                        val matchingTarget = current.values.find {
                            it.deviceId == event.deviceId && it.model.equals(eventModel, ignoreCase = true)
                        } ?: current.values.find { it.uniqueKey == "${event.deviceId}_$eventModel" }

                        if (matchingTarget != null) {
                            val patched = matchingTarget.copy(
                                heartbeatStatus = "online",
                                lastHeartbeat = event.timestamp ?: matchingTarget.lastHeartbeat
                            )
                            val updated = current.toMutableMap()
                            updated[patched.uniqueKey] = patched
                            if (patched.connectionId.isNotBlank()) updated[patched.connectionId] = patched
                            val distinct = updated.values.filter { it.deviceId == patched.deviceId }.distinctBy { it.uniqueKey }
                            if (distinct.size <= 1 || updated[patched.deviceId]?.uniqueKey == patched.uniqueKey) {
                                updated[patched.deviceId] = patched
                            }
                            saveDeviceCache(updated)
                            return@update updated
                        }
                    }

                    val distinctDevices = current.values.filter { it.deviceId == event.deviceId }.distinctBy { it.uniqueKey }
                    if (distinctDevices.size <= 1) {
                        val existing = current[event.deviceId] ?: current.values.firstOrNull { it.deviceId == event.deviceId } ?: return@update current
                        val patched = existing.copy(
                            heartbeatStatus = "online",
                            lastHeartbeat = event.timestamp ?: existing.lastHeartbeat
                        )
                        val updated = current.toMutableMap()
                        updated[patched.uniqueKey] = patched
                        if (patched.connectionId.isNotBlank()) updated[patched.connectionId] = patched
                        updated[event.deviceId] = patched
                        saveDeviceCache(updated)
                        updated
                    } else {
                        // Multiple distinct devices share this ID and model was omitted:
                        // Only refresh the timestamp of the device that is already online, never reviving offline units!
                        val updated = current.toMutableMap()
                        var modified = false
                        for ((k, d) in current) {
                            if (d.deviceId == event.deviceId && d.heartbeatStatus == "online") {
                                updated[k] = d.copy(
                                    lastHeartbeat = event.timestamp ?: d.lastHeartbeat
                                )
                                modified = true
                            }
                        }
                        if (modified) {
                            saveDeviceCache(updated)
                            updated
                        } else {
                            current
                        }
                    }
                }
            }

            is DeviceUpdateEvent.LocationUpdated -> {
                val receiptIso = receiptClock.instant().toString()
                val sampleIso = normalizedSampleIso(event.timestamp)

                _devices.update { current ->
                    val matchingKeys = current.entries
                        .filter { it.value.deviceId == event.deviceId }
                        .map { it.key }
                    if (matchingKeys.isEmpty()) {
                        val existing = current[event.deviceId] ?: return@update current
                        val targetKey = existing.uniqueKey.ifBlank { event.deviceId }
                        incrementLocationRevision(targetKey)

                        val currentLocation = existing.latestLocation
                        val location = if (currentLocation != null) {
                            currentLocation.copy(
                                latitude = event.latitude,
                                longitude = event.longitude,
                                accuracy = event.accuracy ?: 0.0,
                                battery = event.battery ?: currentLocation.battery,
                                network = event.network ?: currentLocation.network,
                                recordedAt = sampleIso.orEmpty(),
                                hasReportedFix = true,
                                receivedAt = receiptIso
                            )
                        } else {
                            DeviceLocation(
                                deviceId = event.deviceId,
                                latitude = event.latitude,
                                longitude = event.longitude,
                                accuracy = event.accuracy ?: 0.0,
                                battery = event.battery ?: -1,
                                network = event.network ?: "unknown",
                                recordedAt = sampleIso.orEmpty(),
                                hasReportedFix = true,
                                receivedAt = receiptIso
                            )
                        }
                        val updated = current + (event.deviceId to existing.copy(latestLocation = location))
                        saveDeviceCache(updated)
                        updated
                    } else {
                        val distinctDevices = current.values.filter { it.deviceId == event.deviceId }.distinctBy { it.uniqueKey }
                        val targetKey: String? = if (!event.model.isNullOrBlank()) {
                            distinctDevices.find { it.model.equals(event.model, ignoreCase = true) }?.uniqueKey
                        } else if (distinctDevices.size == 1) {
                            distinctDevices.first().uniqueKey
                        } else {
                            if (isEchoOfDedicatedTelemetry(event.latitude, event.longitude, event.battery)) {
                                Log.d(TAG, "Suppressed echo of dedicated telemetry (lat=${event.latitude}, bat=${event.battery})")
                                null
                            } else {
                                val nonDedicated = distinctDevices.filter { !dedicatedTelemetryDevices.contains(it.uniqueKey) }
                                if (nonDedicated.size == 1) {
                                    nonDedicated.first().uniqueKey
                                } else {
                                    Log.d(TAG, "Suppressed ambiguous untagged LocationUpdated from server (devices: ${distinctDevices.map { it.model }})")
                                    null
                                }
                            }
                        }

                        if (targetKey == null) {
                            return@update current
                        }

                        incrementLocationRevision(targetKey)

                        val existing = current[targetKey] ?: return@update current
                        val currentLocation = existing.latestLocation
                        val location = if (currentLocation != null) {
                            currentLocation.copy(
                                latitude = event.latitude,
                                longitude = event.longitude,
                                accuracy = event.accuracy ?: 0.0,
                                battery = event.battery ?: currentLocation.battery,
                                network = event.network ?: currentLocation.network,
                                recordedAt = sampleIso.orEmpty(),
                                hasReportedFix = true,
                                receivedAt = receiptIso
                            )
                        } else {
                            DeviceLocation(
                                deviceId = event.deviceId,
                                latitude = event.latitude,
                                longitude = event.longitude,
                                accuracy = event.accuracy ?: 0.0,
                                battery = event.battery ?: -1,
                                network = event.network ?: "unknown",
                                recordedAt = sampleIso.orEmpty(),
                                hasReportedFix = true,
                                receivedAt = receiptIso
                            )
                        }
                        val updated = current.toMutableMap()
                        val patched = existing.copy(latestLocation = location)
                        updated[targetKey] = patched
                        if (patched.uniqueKey != targetKey) updated[patched.uniqueKey] = patched
                        if (patched.connectionId.isNotBlank()) updated[patched.connectionId] = patched
                        val distinct = updated.values.filter { it.deviceId == patched.deviceId }.distinctBy { it.uniqueKey }
                        if (distinct.size <= 1 || updated[patched.deviceId]?.uniqueKey == patched.uniqueKey) {
                            updated[patched.deviceId] = patched
                        }
                        saveDeviceCache(updated)
                        updated
                    }
                }
            }

            is DeviceUpdateEvent.BatteryUpdated -> {
                _devices.update { current ->
                    val matchingKeys = current.entries
                        .filter { it.value.deviceId == event.deviceId }
                        .map { it.key }
                    if (matchingKeys.isEmpty()) {
                        val existing = current[event.deviceId] ?: return@update current
                        val location = existing.latestLocation?.copy(battery = event.battery)
                            ?: return@update current
                        val updated = current + (event.deviceId to existing.copy(latestLocation = location))
                        saveDeviceCache(updated)
                        updated
                    } else {
                        val distinctDevices = current.values.filter { it.deviceId == event.deviceId }.distinctBy { it.uniqueKey }
                        val targetKey: String? = if (!event.model.isNullOrBlank()) {
                            distinctDevices.find { it.model.equals(event.model, ignoreCase = true) }?.uniqueKey
                        } else if (distinctDevices.size == 1) {
                            distinctDevices.first().uniqueKey
                        } else {
                            if (isEchoOfDedicatedTelemetry(0.0, 0.0, event.battery)) {
                                Log.d(TAG, "Suppressed echo of dedicated telemetry battery (${event.battery}%)")
                                null
                            } else {
                                val nonDedicated = distinctDevices.filter { !dedicatedTelemetryDevices.contains(it.uniqueKey) }
                                if (nonDedicated.size == 1) {
                                    nonDedicated.first().uniqueKey
                                } else {
                                    Log.d(TAG, "Suppressed untagged BatteryUpdated from server (${event.battery}%)")
                                    null
                                }
                            }
                        }

                        if (targetKey == null) return@update current

                        val existing = current[targetKey] ?: return@update current
                        val location = existing.latestLocation?.copy(battery = event.battery)
                            ?: DeviceLocation(
                                deviceId = existing.deviceId,
                                latitude = 0.0,
                                longitude = 0.0,
                                accuracy = 0.0,
                                battery = event.battery,
                                network = "unknown",
                                recordedAt = "",
                                hasReportedFix = false,
                                receivedAt = null
                            )
                        val updated = current.toMutableMap()
                        val patched = existing.copy(latestLocation = location)
                        updated[targetKey] = patched
                        if (patched.uniqueKey != targetKey) updated[patched.uniqueKey] = patched
                        if (patched.connectionId.isNotBlank()) updated[patched.connectionId] = patched
                        val distinct = updated.values.filter { it.deviceId == patched.deviceId }.distinctBy { it.uniqueKey }
                        if (distinct.size <= 1 || updated[patched.deviceId]?.uniqueKey == patched.uniqueKey) {
                            updated[patched.deviceId] = patched
                        }
                        saveDeviceCache(updated)
                        updated
                    }
                }
            }

            is DeviceUpdateEvent.NetworkUpdated -> {
                _devices.update { current ->
                    val matchingKeys = current.entries
                        .filter { it.value.deviceId == event.deviceId }
                        .map { it.key }
                    if (matchingKeys.isEmpty()) {
                        val existing = current[event.deviceId] ?: return@update current
                        val location = existing.latestLocation?.copy(network = event.network)
                            ?: return@update current
                        val updated = current + (event.deviceId to existing.copy(latestLocation = location))
                        saveDeviceCache(updated)
                        updated
                    } else {
                        val distinctDevices = current.values.filter { it.deviceId == event.deviceId }.distinctBy { it.uniqueKey }
                        val targetKey: String? = if (!event.model.isNullOrBlank()) {
                            distinctDevices.find { it.model.equals(event.model, ignoreCase = true) }?.uniqueKey
                        } else if (distinctDevices.size == 1) {
                            distinctDevices.first().uniqueKey
                        } else {
                            val nonDedicated = distinctDevices.filter { !dedicatedTelemetryDevices.contains(it.uniqueKey) }
                            if (nonDedicated.size == 1) {
                                nonDedicated.first().uniqueKey
                            } else {
                                null
                            }
                        }

                        if (targetKey == null) return@update current

                        val existing = current[targetKey] ?: return@update current
                        val location = existing.latestLocation?.copy(network = event.network)
                            ?: DeviceLocation(
                                deviceId = existing.deviceId,
                                latitude = 0.0,
                                longitude = 0.0,
                                accuracy = 0.0,
                                battery = -1,
                                network = event.network,
                                recordedAt = "",
                                hasReportedFix = false,
                                receivedAt = null
                            )
                        val updated = current.toMutableMap()
                        val patched = existing.copy(latestLocation = location)
                        updated[targetKey] = patched
                        if (patched.uniqueKey != targetKey) updated[patched.uniqueKey] = patched
                        if (patched.connectionId.isNotBlank()) updated[patched.connectionId] = patched
                        val distinct = updated.values.filter { it.deviceId == patched.deviceId }.distinctBy { it.uniqueKey }
                        if (distinct.size <= 1 || updated[patched.deviceId]?.uniqueKey == patched.uniqueKey) {
                            updated[patched.deviceId] = patched
                        }
                        saveDeviceCache(updated)
                        updated
                    }
                }
            }

            is DeviceUpdateEvent.MetadataUpdated -> {
                _devices.update { current ->
                    val matchingKeys = current.entries
                        .filter { it.value.deviceId == event.deviceId }
                        .map { it.key }
                    if (matchingKeys.isEmpty()) {
                        val existing = current[event.deviceId] ?: return@update current
                        val updated = current + (event.deviceId to existing.copy(
                            deviceName = event.deviceName ?: existing.deviceName,
                            appVersion = event.appVersion ?: existing.appVersion,
                            model = event.model ?: existing.model
                        ))
                        saveDeviceCache(updated)
                        updated
                    } else {
                        val updated = current.toMutableMap()
                        for (k in matchingKeys) {
                            val existing = updated[k]!!
                            updated[k] = existing.copy(
                                deviceName = event.deviceName ?: existing.deviceName,
                                appVersion = event.appVersion ?: existing.appVersion,
                                model = event.model ?: existing.model
                            )
                        }
                        saveDeviceCache(updated)
                        updated
                    }
                }
            }

            is DeviceUpdateEvent.EmergencySos -> {
                Log.e(TAG, "EMERGENCY SOS: deviceId=${event.deviceId}, reason=${event.triggerReason}, impact=${event.impactGForce}g")
                val currentDevices = _devices.value
                val existingDevice = currentDevices.values.firstOrNull {
                    it.deviceId == event.deviceId &&
                    (event.model == null || it.model.equals(event.model, ignoreCase = true))
                } ?: currentDevices[event.deviceId]

                val resolvedModel = event.model ?: existingDevice?.model ?: "Unknown"
                val callsign = existingDevice?.callsign ?: getOrCreateCallsign(
                    uniqueKey = "${event.deviceId}_$resolvedModel",
                    model = resolvedModel
                )

                val alert = com.sentinel.admin.domain.model.EmergencyAlert(
                    deviceId = event.deviceId,
                    callsign = callsign,
                    model = resolvedModel,
                    triggerReason = event.triggerReason,
                    impactGForce = event.impactGForce,
                    latitude = event.latitude ?: existingDevice?.latestLocation?.latitude,
                    longitude = event.longitude ?: existingDevice?.latestLocation?.longitude,
                    accuracy = event.accuracy ?: existingDevice?.latestLocation?.accuracy,
                    battery = event.battery ?: existingDevice?.latestLocation?.battery,
                    timestamp = System.currentTimeMillis()
                )

                _emergencyAlert.value = alert
                notificationManager?.triggerEmergencyAlert(alert)

                // If coordinates or battery provided in SOS, patch device location
                val lat = event.latitude
                val lng = event.longitude
                if (lat != null && lng != null) {
                    val locEvent = DeviceUpdateEvent.LocationUpdated(
                        deviceId = event.deviceId,
                        latitude = lat,
                        longitude = lng,
                        accuracy = event.accuracy,
                        battery = event.battery,
                        network = null,
                        model = event.model,
                        timestamp = event.timestamp
                    )
                    applyEvent(locEvent)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val mapAdapter by lazy {
        moshi.adapter(Map::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleCommandResult(rawJson: String) {
        try {
            val jsonMap = mapAdapter.fromJson(rawJson) as? Map<String, Any?> ?: return
            if (jsonMap["type"] != "COMMAND_RESULT") return
            val data = jsonMap["data"] as? Map<String, Any?> ?: return
            val command = data["command"] as? String
            val payload = data["payload"] as? Map<String, Any?> ?: return

            if (command == "TELEMETRY_REPORT" || (command == "GET_SYSTEM_INFO" && payload.containsKey("batteryPercent"))) {
                handleTelemetryReport(payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command result in DeviceRepository: ${e.message}", e)
        }
    }

    private fun handleTelemetryReport(payload: Map<String, Any?>) {
        val model = (payload["model"] as? String)?.takeIf { it.isNotBlank() }
        val deviceId = (payload["deviceId"] as? String)?.takeIf { it.isNotBlank() } ?: return
        val callsign = (payload["callsign"] as? String)?.takeIf { it.isNotBlank() }
        val hardwareId = (payload["hardwareId"] as? String)?.takeIf { it.isNotBlank() }
        val uniqueKey = (payload["uniqueKey"] as? String)?.takeIf { it.isNotBlank() }
            ?: if (callsign != null) "${deviceId}_$callsign"
            else if (model != null) "${deviceId}_$model"
            else null

        val battery = (payload["battery"] as? Number)?.toInt()
            ?: (payload["batteryPercent"] as? Number)?.toInt()

        val isCharging = payload["isCharging"] as? Boolean ?: false
        val wifiSsid = (payload["wifiSsid"] as? String) ?: ""
        val network = (payload["network"] as? String)?.takeIf { it.isNotBlank() }
            ?: if (isCharging) "WiFi (Charging)" else if (wifiSsid.isNotBlank()) "WiFi" else null

        val lat = (payload["latitude"] as? Number)?.toDouble()
        val lng = (payload["longitude"] as? Number)?.toDouble()
        val accuracy = (payload["accuracy"] as? Number)?.toDouble()
        val rawTime = payload["timestamp"] ?: payload["sampleTime"] ?: payload["time"]
        val sampleIso = normalizedEpochToIso(rawTime)
        val receiptIso = receiptClock.instant().toString()

        _devices.update { current ->
            val candidates = current.values.distinctBy { it.uniqueKey }

            // Exact permanent identity wins even when another phone of the same model is online.
            val exact = candidates.filter { it.deviceId == deviceId && deviceId != "HOST-001" }
            val keyed = candidates.filter { uniqueKey != null && it.uniqueKey == uniqueKey }
            val hardware = candidates.filter {
                (!callsign.isNullOrBlank() && (it.resolvedCallsign == callsign || it.callsign == callsign)) ||
                (!hardwareId.isNullOrBlank() && (it.hardwareId == hardwareId ||
                    it.deviceId == "HOST-001-$hardwareId" || it.deviceId == "HOST-$hardwareId"))
            }
            val legacy = candidates.filter {
                it.deviceId == deviceId && (model == null || it.model.equals(model, ignoreCase = true))
            }
            val matches = when {
                deviceId != "HOST-001" -> exact.ifEmpty { hardware.filter { it.deviceId == "HOST-001" } }
                keyed.isNotEmpty() -> keyed
                hardware.isNotEmpty() -> hardware
                !callsign.isNullOrBlank() || !hardwareId.isNullOrBlank() -> emptyList()
                else -> legacy
            }
            if (matches.size > 1) return@update current
            // A generic untagged report must not choose an arbitrary member of the fleet.
            if (deviceId == "HOST-001" && matches.isEmpty() && uniqueKey == null) return@update current
            val targetDevice = matches.singleOrNull()
            val targetKey = targetDevice?.uniqueKey ?: uniqueKey ?: deviceId

            val finalKey = targetKey
            val hasFix = coordinatesAreValid(lat, lng)
            if (battery != null || hasFix) {
                recentDedicatedSnapshots.add(
                    DedicatedSnapshot(
                        deviceKey = finalKey,
                        latitude = lat ?: 0.0,
                        longitude = lng ?: 0.0,
                        battery = battery ?: -1,
                        network = network ?: "",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            dedicatedTelemetryDevices.add(finalKey)
            if (uniqueKey != null) {
                dedicatedTelemetryDevices.add(uniqueKey)
            }
            if (model != null) {
                dedicatedTelemetryDevices.add("${deviceId}_$model")
            }

            val existing = current[targetKey]
            val prevLoc = existing?.latestLocation

            if (hasFix) {
                incrementLocationRevision(targetKey)
            }

            val updatedLocation = if (hasFix) {
                (prevLoc ?: DeviceLocation(
                    deviceId = deviceId,
                    latitude = lat!!,
                    longitude = lng!!,
                    accuracy = accuracy ?: 0.0,
                    battery = battery ?: -1,
                    network = network ?: "unknown",
                    recordedAt = sampleIso.orEmpty(),
                    hasReportedFix = true,
                    receivedAt = receiptIso
                )).copy(
                    latitude = lat!!,
                    longitude = lng!!,
                    accuracy = accuracy ?: 0.0,
                    battery = battery ?: prevLoc?.battery ?: -1,
                    network = network ?: prevLoc?.network ?: "unknown",
                    recordedAt = sampleIso.orEmpty(),
                    hasReportedFix = true,
                    receivedAt = receiptIso
                )
            } else if (battery != null || network != null) {
                prevLoc?.copy(
                    battery = battery ?: prevLoc.battery,
                    network = network ?: prevLoc.network
                ) ?: DeviceLocation(
                    deviceId = deviceId,
                    latitude = 0.0,
                    longitude = 0.0,
                    accuracy = 0.0,
                    battery = battery ?: -1,
                    network = network ?: "unknown",
                    recordedAt = "",
                    hasReportedFix = false,
                    receivedAt = null
                )
            } else {
                prevLoc
            }

            val currentUtc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())

            val existingCallsign = existing?.callsign?.ifBlank { null }
            val resolvedCallsign = existingCallsign ?: getOrCreateCallsign(targetKey, model ?: "")
            val patched = (existing ?: Device(
                deviceId = deviceId,
                connectionId = "",
                authenticated = true,
                registered = true,
                registrationState = "registered",
                heartbeatStatus = "online",
                connectedAt = "",
                lastHeartbeat = currentUtc,
                deviceName = if (resolvedCallsign.isNotBlank()) "$model ($resolvedCallsign)" else (model ?: deviceId),
                appVersion = "",
                model = model ?: "",
                latestLocation = updatedLocation,
                callsign = resolvedCallsign,
                hardwareId = hardwareId ?: ""
            )).copy(
                deviceId = if (deviceId != "HOST-001") deviceId else existing?.deviceId ?: deviceId,
                latestLocation = updatedLocation,
                heartbeatStatus = "online",
                lastHeartbeat = currentUtc,
                model = model ?: existing?.model ?: "",
                callsign = resolvedCallsign,
                hardwareId = hardwareId ?: existing?.hardwareId ?: ""
            )

            val updated = current.toMutableMap()
            if (existing != null) {
                updated.entries.removeAll { it.value.uniqueKey == existing.uniqueKey }
            }
            updated[targetKey] = patched
            if (patched.uniqueKey != targetKey) {
                updated[patched.uniqueKey] = patched
            }
            if (patched.connectionId.isNotBlank()) {
                updated[patched.connectionId] = patched
            }
            val distinct = updated.values.filter { it.deviceId == patched.deviceId }.distinctBy { it.uniqueKey }
            if (distinct.size <= 1 || updated[patched.deviceId]?.uniqueKey == patched.uniqueKey) {
                updated[patched.deviceId] = patched
            }
            saveDeviceCache(updated)
            updated
        }
    }

    /**
     * Creates a minimal device entry when a "connected" event arrives
     * for a device not yet in the map (e.g., connected after initial REST load).
     */
    private fun createMinimalDevice(event: DeviceUpdateEvent.DeviceConnected): Device {
        val model = event.model ?: ""
        val hwCallsign = Regex("""\((HOST-[A-Za-z0-9_-]+)\)""").find(event.deviceName ?: "")?.groupValues?.get(1) ?: ""
        val uniqueKey = if (hwCallsign.isNotBlank()) "${event.deviceId}_$hwCallsign"
            else if (model.isNotBlank()) "${event.deviceId}_$model"
            else event.deviceId
        val callsign = hwCallsign.ifBlank { getOrCreateCallsign(uniqueKey, model) }
        return Device(
            deviceId = event.deviceId,
            connectionId = "",
            authenticated = true,
            registered = true,
            registrationState = "registered",
            heartbeatStatus = "online",
            connectedAt = "",
            lastHeartbeat = "",
            deviceName = event.deviceName?.takeIf { it.isNotBlank() } ?: event.deviceId,
            appVersion = event.appVersion ?: "",
            model = model,
            latestLocation = null,
            callsign = callsign
        )
    }

    private fun mapHttpError(e: HttpException): Exception {
        return when (e.code()) {
            401 -> IllegalStateException("Unauthorized — invalid or expired token")
            403 -> IllegalStateException("Forbidden")
            404 -> NoSuchElementException("Device not found")
            500 -> RuntimeException("Server error")
            else -> RuntimeException("HTTP ${e.code()}: ${e.message()}")
        }
    }
}

internal fun normalizedSampleIso(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(value).toString() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant().toString() }.getOrNull()
}

internal fun normalizedEpochToIso(raw: Any?): String? {
    when (raw) {
        null -> return null
        is Number -> {
            val n = raw.toLong()
            if (n <= 0L) return null
            val instant = if (n < 100_000_000_000L) {
                Instant.ofEpochSecond(n)
            } else {
                Instant.ofEpochMilli(n)
            }
            return instant.toString()
        }
        is String -> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val asLong = trimmed.toLongOrNull()
            if (asLong != null) {
                return normalizedEpochToIso(asLong)
            }
            return normalizedSampleIso(trimmed)
        }
        else -> return null
    }
}

internal fun coordinatesAreValid(lat: Double?, lon: Double?): Boolean =
    lat != null && lon != null &&
        lat.isFinite() && lon.isFinite() &&
        lat in -90.0..90.0 && lon in -180.0..180.0

