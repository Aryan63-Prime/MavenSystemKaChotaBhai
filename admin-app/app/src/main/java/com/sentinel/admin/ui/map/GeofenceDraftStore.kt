package com.sentinel.admin.ui.map

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed admin-local draft models.
 * Preserves truthfulness: Stored solely in admin application memory / SharedPreferences;
 * not enforced by host or backend database.
 */
sealed interface GeofenceDraft {
    val deviceId: String
    val createdAt: Long
    val type: String get() = when (this) {
        is Circle -> "CIRCLE"
        is Polygon -> "POLYGON"
    }

    data class Circle(
        override val deviceId: String,
        val centerLat: Double,
        val centerLon: Double,
        val radiusMeters: Float,
        override val createdAt: Long = System.currentTimeMillis()
    ) : GeofenceDraft

    data class Polygon(
        override val deviceId: String,
        val vertices: List<Pair<Double, Double>>,
        override val createdAt: Long = System.currentTimeMillis()
    ) : GeofenceDraft
}

/**
 * Durable thread-safe admin-local draft store keyed by stable device key.
 * Backed by Android SharedPreferences for process-restart durability.
 *
 * Implements Work Orders 2, 3 & 4:
 * - Offloads cold reads and durable disk writes to IO dispatcher
 * - Uses standard JSON parser (JSONObject/JSONArray) with backward compatibility
 * - Preserves decimal radius and multi-vertex polygon geometries
 * - Synchronous commit on IO dispatcher ensures honest failure reporting
 */
class GeofenceDraftStore(
    private val prefs: SharedPreferences? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val lock = Any()
    private val memoryCache = mutableMapOf<String, GeofenceDraft>()

    private fun prefKey(deviceId: String): String = "geofence_draft_$deviceId"

    private fun loadAllFromPrefsInternal() = synchronized(lock) {
        val p = prefs ?: return@synchronized
        try {
            val allEntries = p.all
            for ((key, value) in allEntries) {
                if (key.startsWith("geofence_draft_") && value is String) {
                    deserializeDraft(value)?.let { draft ->
                        memoryCache[draft.deviceId] = draft
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun preload(): Unit = withContext(ioDispatcher) {
        loadAllFromPrefsInternal()
    }

    suspend fun saveDraft(draft: GeofenceDraft): Boolean = withContext(ioDispatcher) {
        saveDraftBlocking(draft)
    }

    fun saveDraftBlocking(draft: GeofenceDraft): Boolean = synchronized(lock) {
        val json = serializeDraft(draft)
        val p = prefs
        if (p != null) {
            val committed = try {
                p.edit().putString(prefKey(draft.deviceId), json).commit()
            } catch (_: Exception) {
                false
            }
            if (!committed) {
                return false
            }
        }
        memoryCache[draft.deviceId] = draft
        true
    }

    suspend fun getDraft(deviceId: String): GeofenceDraft? = withContext(ioDispatcher) {
        getDraftBlocking(deviceId)
    }

    fun getDraftBlocking(deviceId: String): GeofenceDraft? = synchronized(lock) {
        memoryCache[deviceId] ?: run {
            val p = prefs ?: return@synchronized null
            val json = p.getString(prefKey(deviceId), null) ?: return@synchronized null
            val draft = deserializeDraft(json)
            if (draft != null) {
                memoryCache[deviceId] = draft
            }
            draft
        }
    }

    suspend fun clearDraft(deviceId: String): Boolean = withContext(ioDispatcher) {
        clearDraftBlocking(deviceId)
    }

    suspend fun deleteDraft(deviceId: String): Boolean = clearDraft(deviceId)

    fun clearDraftBlocking(deviceId: String): Boolean = synchronized(lock) {
        val p = prefs
        if (p != null) {
            val committed = try {
                p.edit().remove(prefKey(deviceId)).commit()
            } catch (_: Exception) {
                false
            }
            if (!committed) {
                return false
            }
        }
        val removed = memoryCache.remove(deviceId) != null
        p != null || removed
    }

    suspend fun hasDraft(deviceId: String): Boolean = withContext(ioDispatcher) {
        hasDraftBlocking(deviceId)
    }

    fun hasDraftBlocking(deviceId: String): Boolean = synchronized(lock) {
        memoryCache.containsKey(deviceId) || (prefs?.contains(prefKey(deviceId)) == true)
    }

    companion object {
        private const val PREFS_NAME = "sentinel_geofence_drafts"

        @Volatile
        private var defaultInstance: GeofenceDraftStore? = null

        fun getDefault(context: Context? = null): GeofenceDraftStore {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: run {
                    val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    GeofenceDraftStore(prefs).also { defaultInstance = it }
                }
            }
        }

        fun serializeDraft(draft: GeofenceDraft): String {
            val json = JSONObject()
            when (draft) {
                is GeofenceDraft.Circle -> {
                    json.put("type", "circle")
                    json.put("deviceId", draft.deviceId)
                    json.put("centerLat", draft.centerLat)
                    json.put("centerLon", draft.centerLon)
                    json.put("radiusMeters", draft.radiusMeters.toDouble())
                    json.put("createdAt", draft.createdAt)
                }
                is GeofenceDraft.Polygon -> {
                    json.put("type", "polygon")
                    json.put("deviceId", draft.deviceId)
                    val vArr = JSONArray()
                    draft.vertices.forEach { (lat, lon) ->
                        val pairArr = JSONArray()
                        pairArr.put(lat)
                        pairArr.put(lon)
                        vArr.put(pairArr)
                    }
                    json.put("vertices", vArr)
                    json.put("createdAt", draft.createdAt)
                }
            }
            return json.toString()
        }

        fun deserializeDraft(json: String): GeofenceDraft? {
            return try {
                val obj = JSONObject(json.trim())
                val type = obj.optString("type", "")
                val deviceId = obj.optString("deviceId", "")
                if (deviceId.isBlank()) return null
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())

                when (type) {
                    "circle" -> {
                        val lat = obj.optDouble("centerLat", Double.NaN)
                        val lon = obj.optDouble("centerLon", Double.NaN)
                        val radius = obj.optDouble("radiusMeters", Double.NaN).toFloat()
                        if (lat.isNaN() || lon.isNaN() || radius.isNaN()) return null
                        GeofenceDraft.Circle(
                            deviceId = deviceId,
                            centerLat = lat,
                            centerLon = lon,
                            radiusMeters = radius,
                            createdAt = createdAt
                        )
                    }
                    "polygon" -> {
                        val vArr = obj.optJSONArray("vertices") ?: return null
                        val vertices = mutableListOf<Pair<Double, Double>>()
                        for (i in 0 until vArr.length()) {
                            val pairArr = vArr.optJSONArray(i)
                            if (pairArr != null && pairArr.length() >= 2) {
                                val lat = pairArr.optDouble(0, Double.NaN)
                                val lon = pairArr.optDouble(1, Double.NaN)
                                if (!lat.isNaN() && !lon.isNaN()) {
                                    vertices.add(lat to lon)
                                }
                            }
                        }
                        if (vertices.isEmpty()) return null
                        GeofenceDraft.Polygon(
                            deviceId = deviceId,
                            vertices = vertices,
                            createdAt = createdAt
                        )
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
