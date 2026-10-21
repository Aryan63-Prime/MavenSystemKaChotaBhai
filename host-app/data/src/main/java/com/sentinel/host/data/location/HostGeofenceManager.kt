package com.sentinel.host.data.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.sentinel.host.data.receiver.GeofenceBroadcastReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class GeofenceZone(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val expirationDurationMs: Long = Geofence.NEVER_EXPIRE
)

@Singleton
class HostGeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Sentinel:GeofenceManager"
    }

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    private val activeZones = java.util.concurrent.CopyOnWriteArrayList<GeofenceZone>()
    private val breachedZoneIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    fun registerZones(zones: List<GeofenceZone>, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        if (zones.isEmpty()) return

        activeZones.clear()
        activeZones.addAll(zones)
        breachedZoneIds.clear()

        val geofenceList = zones.map { zone ->
            Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(zone.latitude, zone.longitude, zone.radiusMeters)
                .setExpirationDuration(zone.expirationDurationMs)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofenceList)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent).run {
            addOnSuccessListener {
                Log.i(TAG, "Successfully registered ${zones.size} hardware-assisted geofences")
                onSuccess()
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to register geofences: ${e.message}", e)
                onError(e.message ?: "Unknown geofence registration error")
            }
        }
    }

    fun clearAllGeofences() {
        activeZones.clear()
        breachedZoneIds.clear()
        geofencingClient.removeGeofences(geofencePendingIntent).addOnCompleteListener {
            Log.i(TAG, "All active geofences removed")
        }
    }

    fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        return try {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            results[0]
        } catch (_: Throwable) {
            val r = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2).let { it * it }
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            (r * c).toFloat()
        }
    }

    fun evaluateLocationForBreach(latitude: Double, longitude: Double): GeofenceZone? {
        for (zone in activeZones) {
            val dist = computeDistanceMeters(latitude, longitude, zone.latitude, zone.longitude)
            if (dist > zone.radiusMeters) {
                if (breachedZoneIds.add(zone.id)) {
                    Log.w(TAG, "Geofence breached! Zone ${zone.id}, dist=$dist > radius=${zone.radiusMeters}")
                    return zone
                }
            } else if (dist < zone.radiusMeters * 0.85f) {
                breachedZoneIds.remove(zone.id)
            }
        }
        return null
    }

    fun getActiveZones(): List<GeofenceZone> = activeZones.toList()
}
