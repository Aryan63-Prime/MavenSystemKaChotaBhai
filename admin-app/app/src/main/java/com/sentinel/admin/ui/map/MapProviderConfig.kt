package com.sentinel.admin.ui.map

/**
 * OpenFreeMap vector tile provider configuration for MapLibre Native.
 * Aligned with project specifications:
 * - Direct HTTPS requests to OpenFreeMap tile infrastructure
 * - Starter style: https://tiles.openfreemap.org/styles/liberty
 * - Preserves visible OpenFreeMap, OpenMapTiles, and OpenStreetMap attribution
 */
data class MapProviderConfig(
    val styleUrl: String = "https://tiles.openfreemap.org/styles/liberty",
    val attribution: String = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors",
    val minZoom: Double = 2.0,
    val maxZoom: Double = 19.0,
    val defaultZoom: Double = 15.0
)

val defaultMapProvider = MapProviderConfig()

/**
 * Validates geographic coordinate values using GeofenceGeometryValidator.
 * - Missing / null is not defaulted to (0, 0)
 * - Checks finite values
 * - Latitude must be within [-90.0, 90.0]
 * - Longitude must be within [-180.0, 180.0]
 */
fun isValidCoordinate(latitude: Double?, longitude: Double?): Boolean =
    GeofenceGeometryValidator.validateCoordinate(latitude, longitude).isValid

/**
 * Validates circular geofence radius enforcing 10m minimum to 50,000m maximum sanity policy.
 */
fun isValidRadius(radiusMeters: Float?): Boolean =
    GeofenceGeometryValidator.validateRadius(radiusMeters).isValid
