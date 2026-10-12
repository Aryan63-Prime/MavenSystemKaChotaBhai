package com.sentinel.admin.ui.map

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * Result status for opening a device point in external mapping applications.
 */
enum class MapsLaunchResult {
    OPENED,
    INVALID_COORDINATE,
    UNAVAILABLE
}

/**
 * Production helper for launching external map providers (Google Maps app,
 * geo: intent handler, or Google Maps HTTPS URL) with exact selected device coordinates.
 *
 * Implements Work Order 8.D:
 * - Full numeric coordinate precision with US locale formatting
 * - Genuine (0.0, 0.0) coordinates are validated and supported
 * - Graceful fallback from Google Maps package to geo: handler to web URL
 * - Does not require a Google Maps API key or navigation permissions
 */
object MapsLauncher {

    fun formatCoordinates(latitude: Double, longitude: Double): String = "$latitude,$longitude"

    fun buildGeoUriString(latitude: Double, longitude: Double, label: String): String {
        val point = formatCoordinates(latitude, longitude)
        val encodedQuery = java.net.URLEncoder.encode("$point ($label)", "UTF-8")
        return "geo:$point?q=$encodedQuery"
    }

    fun buildWebUriString(latitude: Double, longitude: Double): String {
        val point = formatCoordinates(latitude, longitude)
        return "https://www.google.com/maps/search/?api=1&query=$point"
    }

    fun buildGeoUri(latitude: Double, longitude: Double, label: String): Uri {
        return Uri.parse(buildGeoUriString(latitude, longitude, label))
    }

    fun buildWebUri(latitude: Double, longitude: Double): Uri {
        return Uri.parse(buildWebUriString(latitude, longitude))
    }

    fun openDevicePoint(
        context: Context,
        latitude: Double?,
        longitude: Double?,
        label: String
    ): MapsLaunchResult {
        if (!isValidCoordinate(latitude, longitude)) {
            return MapsLaunchResult.INVALID_COORDINATE
        }

        val lat = latitude!!
        val lon = longitude!!
        val geoUri = buildGeoUri(lat, lon, label)
        val webUri = buildWebUri(lat, lon)

        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, geoUri).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, geoUri),
            Intent(Intent.ACTION_VIEW, webUri)
        )

        for (intent in candidates) {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return MapsLaunchResult.OPENED
            } catch (_: ActivityNotFoundException) {
                // Try next supported handler
            } catch (_: SecurityException) {
                // Blocked handler; fall through safely
            }
        }

        return MapsLaunchResult.UNAVAILABLE
    }
}
