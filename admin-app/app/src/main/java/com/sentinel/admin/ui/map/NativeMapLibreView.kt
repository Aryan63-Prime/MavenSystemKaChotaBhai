package com.sentinel.admin.ui.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sentinel.admin.ui.dashboard.DeviceMarker
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * State machine representing native MapLibre style lifecycle.
 */
sealed interface MapStyleState {
    data object Loading : MapStyleState
    data object Ready : MapStyleState
    data class Error(val message: String) : MapStyleState
}

/**
 * Production style request ownership controller.
 *
 * Implements Work Order 1:
 * - Generates unique attempt IDs for every actual load attempt
 * - Validates originating attempt for terminal outcomes (success, error, timeout)
 * - Guards against obsolete callbacks after disposal or URL/map replacement
 */
class StyleAttemptOwner {
    private var next = 0L
    private var active: Long? = null

    fun begin(): Long = (++next).also { active = it }
    fun isCurrent(id: Long): Boolean = active == id

    fun finish(id: Long): Boolean {
        if (!isCurrent(id)) return false
        active = null
        return true
    }

    fun invalidate() {
        active = null
    }

    fun current(): Long? = active
}

/**
 * Compact saveable camera state preserving position, orientation, and follow mode across recreation.
 *
 * Implements Work Order 2:
 * - Stores latitude, longitude, zoom, bearing, tilt, and follow mode
 * - Restores once upon native map readiness without overriding user gestures
 */
data class CameraSnapshot(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double,
    val tilt: Double,
    val following: Boolean,
    val isRestored: Boolean = true
)

val cameraSaver = listSaver<CameraSnapshot, Any>(
    save = {
        listOf(
            it.latitude,
            it.longitude,
            it.zoom,
            it.bearing,
            it.tilt,
            it.following,
            it.isRestored
        )
    },
    restore = {
        CameraSnapshot(
            latitude = it[0] as Double,
            longitude = it[1] as Double,
            zoom = it[2] as Double,
            bearing = it[3] as Double,
            tilt = it[4] as Double,
            following = it[5] as Boolean,
            isRestored = it[6] as Boolean
        )
    }
)

/**
 * Shared MapLibre Native single-device map adapter backed by OpenFreeMap vector tiles.
 *
 * Implements Work Orders 1, 2, 8.E & 10:
 * - StyleAttemptOwner: Single explicit style request owner for load, retry, and replacement
 * - Bounded 12s timeout tied strictly to the originating attempt
 * - Compact CameraSnapshot restoration across screen rotation/tab switching
 * - Top-right vertical control rail: Custom North-pointing compass and 48dp recenter button (no SDK compass collision)
 * - SDK compass disabled (isCompassEnabled = false) while rotation gestures remain operable
 * - Single-owner lifecycle and supported memory pressure forwarding (TRIM_MEMORY_RUNNING_LOW/COMPLETE)
 * - Accessible built-in OpenFreeMap attribution
 */
@Composable
fun NativeMapLibreView(
    latitude: Double?,
    longitude: Double?,
    accuracyMeters: Float = 10f,
    deviceName: String = "Device",
    isOnline: Boolean = true,
    config: MapProviderConfig = defaultMapProvider,
    routeCoordinates: List<Pair<Double, Double>> = emptyList(),
    predictedInterceptPoint: Pair<Double, Double>? = null,
    modifier: Modifier = Modifier
) {
    if (!isValidCoordinate(latitude, longitude)) {
        InvalidCoordinatesCard(
            title = "Coordinates Unavailable",
            subtitle = "Device has not broadcast valid GPS fixes",
            modifier = modifier
        )
        return
    }

    val lat = latitude!!
    val lon = longitude!!
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember(context) {
        MapLibre.getInstance(context)
    }

    var retryGeneration by rememberSaveable { mutableIntStateOf(0) }
    var styleState by remember { mutableStateOf<MapStyleState>(MapStyleState.Loading) }
    var isFollowing by rememberSaveable { mutableStateOf(true) }
    var savedCamera by rememberSaveable(stateSaver = cameraSaver) {
        mutableStateOf(
            CameraSnapshot(
                latitude = lat,
                longitude = lon,
                zoom = config.defaultZoom,
                bearing = 0.0,
                tilt = 0.0,
                following = true,
                isRestored = false
            )
        )
    }
    var currentBearing by remember { mutableDoubleStateOf(0.0) }

    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var markerInstance by remember { mutableStateOf<Marker?>(null) }
    var interceptMarkerInstance by remember { mutableStateOf<Marker?>(null) }
    var isDisposed by remember { mutableStateOf(false) }

    val styleOwner = remember { StyleAttemptOwner() }

    val mapView = remember(context) {
        MapView(context).apply {
            onCreate(null)
        }
    }

    // Memory pressure forwarding according to documented SDK support
    DisposableEffect(context, mapView) {
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                    mapView.onLowMemory()
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {
                mapView.onLowMemory()
            }
        }
        context.registerComponentCallbacks(memoryCallbacks)
        onDispose {
            context.unregisterComponentCallbacks(memoryCallbacks)
        }
    }

    // Single-owner balanced lifecycle and once-only teardown
    DisposableEffect(lifecycleOwner, mapView) {
        var isDestroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    if (!isDestroyed) {
                        isDestroyed = true
                        isDisposed = true
                        styleOwner.invalidate()
                        mapView.onDestroy()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            isDisposed = true
            styleOwner.invalidate()
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!isDestroyed) {
                isDestroyed = true
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    // Current inputs captured via rememberUpdatedState so async completions render latest coordinates
    val currentLat by rememberUpdatedState(lat)
    val currentLon by rememberUpdatedState(lon)
    val currentDeviceName by rememberUpdatedState(deviceName)
    val currentAccuracy by rememberUpdatedState(accuracyMeters)
    val currentInterceptPoint by rememberUpdatedState(predictedInterceptPoint)

    fun renderLatestMarker(map: MapLibreMap) {
        val targetPos = LatLng(currentLat, currentLon)
        markerInstance?.let { marker ->
            marker.position = targetPos
            marker.title = currentDeviceName
            marker.snippet = "Accuracy ±${currentAccuracy.toInt()}m"
        } ?: run {
            markerInstance = map.addMarker(
                MarkerOptions()
                    .position(targetPos)
                    .title(currentDeviceName)
                    .snippet("Accuracy ±${currentAccuracy.toInt()}m")
            )
        }
    }

    fun renderInterceptMarker(map: MapLibreMap) {
        val point = currentInterceptPoint
        if (point != null && isValidCoordinate(point.first, point.second)) {
            val targetPos = LatLng(point.first, point.second)
            interceptMarkerInstance?.let { marker ->
                marker.position = targetPos
                marker.title = "🎯 +15m Projected Intercept"
            } ?: run {
                interceptMarkerInstance = map.addMarker(
                    MarkerOptions()
                        .position(targetPos)
                        .title("🎯 +15m Projected Intercept")
                )
            }
        } else {
            interceptMarkerInstance?.let { marker ->
                try {
                    map.removeMarker(marker)
                } catch (_: Exception) {}
                interceptMarkerInstance = null
            }
        }
    }

    var lastAdvisoryError by remember { mutableStateOf<String?>(null) }

    // Style attempt ownership: triggered only by map readiness, style URL changes, or explicit Retry
    LaunchedEffect(maplibreMapInstance, config.styleUrl, retryGeneration) {
        val map = maplibreMapInstance ?: return@LaunchedEffect
        val attempt = styleOwner.begin()
        styleState = MapStyleState.Loading

        map.setStyle(config.styleUrl) { style ->
            if (styleOwner.finish(attempt) && !isDisposed) {
                styleState = MapStyleState.Ready
                map.clear()
                markerInstance = null
                interceptMarkerInstance = null
                renderLatestMarker(map)
                renderInterceptMarker(map)
                updateRoutePolyline(style, routeCoordinates)
                updateInterceptVector(style, currentLat, currentLon, currentInterceptPoint)
            }
        }

        // Bounded 12s timeout tied strictly to this attempt
        delay(12_000)
        if (styleOwner.isCurrent(attempt) && !isDisposed && styleState is MapStyleState.Loading) {
            if (styleOwner.finish(attempt)) {
                styleState = MapStyleState.Error(lastAdvisoryError ?: "Tile request timed out. Please check network connection.")
            }
        }
    }

    // Dynamic Route Polyline sync
    LaunchedEffect(routeCoordinates, maplibreMapInstance, styleState) {
        val map = maplibreMapInstance ?: return@LaunchedEffect
        if (styleState is MapStyleState.Ready) {
            map.getStyle { style ->
                updateRoutePolyline(style, routeCoordinates)
            }
        }
    }

    // Dynamic Intercept Vector and Marker sync
    LaunchedEffect(currentInterceptPoint, currentLat, currentLon, maplibreMapInstance, styleState) {
        val map = maplibreMapInstance ?: return@LaunchedEffect
        if (styleState is MapStyleState.Ready) {
            renderInterceptMarker(map)
            map.getStyle { style ->
                updateInterceptVector(style, currentLat, currentLon, currentInterceptPoint)
            }
        }
    }

    // Telemetry updates decoupled from style request and user camera
    LaunchedEffect(lat, lon, accuracyMeters, deviceName, maplibreMapInstance) {
        val map = maplibreMapInstance ?: return@LaunchedEffect
        if (styleState is MapStyleState.Ready) {
            renderLatestMarker(map)
            if (isFollowing) {
                map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, FigmaCardBorder, RoundedCornerShape(20.dp))
    ) {
        AndroidView(
            factory = {
                mapView.apply {
                    val failListener = MapView.OnDidFailLoadingMapListener { error ->
                        // MapLibre onDidFailLoadingMap is a map-level callback without request correlation.
                        // Handled as advisory diagnostic state per Pass 5 Work Order 5.
                        lastAdvisoryError = error ?: "Failed to load map tiles"
                    }
                    addOnDidFailLoadingMapListener(failListener)

                    getMapAsync { map ->
                        if (isDisposed) return@getMapAsync
                        maplibreMapInstance = map

                        // Disable SDK compass to prevent overlap with custom control rail
                        map.uiSettings.isCompassEnabled = false
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isLogoEnabled = false

                        // User gesture moves cancel following
                        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                            override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                                isFollowing = false
                            }
                            override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                            override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                        })

                        // Real-time continuous compass updates during camera movement
                        map.addOnCameraMoveListener {
                            currentBearing = map.cameraPosition.bearing
                        }

                        // Save settled camera snapshot
                        map.addOnCameraIdleListener {
                            val pos = map.cameraPosition
                            val target = pos.target
                            currentBearing = pos.bearing
                            savedCamera = CameraSnapshot(
                                latitude = target?.latitude ?: lat,
                                longitude = target?.longitude ?: lon,
                                zoom = pos.zoom,
                                bearing = pos.bearing,
                                tilt = pos.tilt,
                                following = isFollowing,
                                isRestored = true
                            )
                        }

                        // Restore camera once or apply initial target
                        val initialTarget = if (savedCamera.isRestored) {
                            LatLng(savedCamera.latitude, savedCamera.longitude)
                        } else {
                            LatLng(lat, lon)
                        }
                        map.cameraPosition = CameraPosition.Builder()
                            .target(initialTarget)
                            .zoom(if (savedCamera.isRestored) savedCamera.zoom else config.defaultZoom)
                            .bearing(if (savedCamera.isRestored) savedCamera.bearing else 0.0)
                            .tilt(if (savedCamera.isRestored) savedCamera.tilt else 0.0)
                            .build()
                        currentBearing = map.cameraPosition.bearing
                        isFollowing = savedCamera.following
                    }
                }
            },
            update = {},
            modifier = Modifier.fillMaxSize()
        )

        // Loading overlay
        if (styleState is MapStyleState.Loading) {
            MapLoadingOverlay(text = "Loading OpenFreeMap tiles…")
        }

        // Style Error state with retry
        if (styleState is MapStyleState.Error) {
            val errorMsg = (styleState as MapStyleState.Error).message
            MapErrorOverlay(
                message = errorMsg,
                onRetry = {
                    retryGeneration++
                }
            )
        }

        // Top-right vertical control rail: Compass + Recenter
        if (styleState is MapStyleState.Ready) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Compass slot: rotates to point North; click resets bearing to North
                CompassControl(
                    bearing = currentBearing,
                    onResetNorth = {
                        maplibreMapInstance?.let { map ->
                            val cur = map.cameraPosition
                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder(cur).bearing(0.0).build()
                                ),
                                350
                            )
                        }
                    }
                )

                // Recenter slot: centers device and resumes tracking
                RecenterControl(
                    isFollowing = isFollowing,
                    isFleet = false,
                    onClick = {
                        isFollowing = true
                        maplibreMapInstance?.animateCamera(
                            CameraUpdateFactory.newLatLng(LatLng(lat, lon))
                        )
                    }
                )
            }
        }
    }
}

/**
 * Shared MapLibre Native fleet map adapter showing all online/offline devices.
 *
 * Implements Work Orders 1, 2, 7, 8.E & 10:
 * - StyleAttemptOwner: Explicit request owner for fleet load, retry, and replacement
 * - Native marker identity mapped to stable device.uniqueKey (never name or index)
 * - LatLngBounds framing for multi-device fleet
 * - Top-right vertical control rail with non-overlapping Compass and Fit Fleet buttons
 * - Truthful empty state: "No locations to display" (never falsely claims no enrolled devices)
 */
@Composable
fun NativeFleetMapLibreView(
    markers: List<DeviceMarker>,
    onMarkerClick: (String) -> Unit,
    config: MapProviderConfig = defaultMapProvider,
    modifier: Modifier = Modifier
) {
    val validMarkers = remember(markers) {
        markers.filter { isValidCoordinate(it.latitude, it.longitude) }
    }

    if (validMarkers.isEmpty()) {
        InvalidCoordinatesCard(
            title = "No Locations to Display",
            subtitle = "No coordinates available for current fleet selection",
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember(context) {
        MapLibre.getInstance(context)
    }

    var retryGeneration by rememberSaveable { mutableIntStateOf(0) }
    var styleState by remember { mutableStateOf<MapStyleState>(MapStyleState.Loading) }
    var isFollowing by rememberSaveable { mutableStateOf(true) }
    var cameraInitializationComplete by remember { mutableStateOf(false) }
    var lastAdvisoryError by remember { mutableStateOf<String?>(null) }
    var savedCamera by rememberSaveable(stateSaver = cameraSaver) {
        mutableStateOf(
            CameraSnapshot(
                latitude = validMarkers.first().latitude,
                longitude = validMarkers.first().longitude,
                zoom = 12.0,
                bearing = 0.0,
                tilt = 0.0,
                following = true,
                isRestored = false
            )
        )
    }
    var currentBearing by remember { mutableDoubleStateOf(0.0) }

    var maplibreMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var isDisposed by remember { mutableStateOf(false) }

    val styleOwner = remember { StyleAttemptOwner() }

    val mapView = remember(context) {
        MapView(context).apply {
            onCreate(null)
        }
    }

    val currentOnMarkerClick by rememberUpdatedState(onMarkerClick)
    val currentMarkers by rememberUpdatedState(validMarkers)
    val deviceKeyByMarkerId = remember(mapView) { mutableMapOf<Long, String>() }

    // Memory callbacks forwarding
    DisposableEffect(context, mapView) {
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                    mapView.onLowMemory()
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {
                mapView.onLowMemory()
            }
        }
        context.registerComponentCallbacks(memoryCallbacks)
        onDispose {
            context.unregisterComponentCallbacks(memoryCallbacks)
        }
    }

    // Lifecycle bridging with single-owner once-only teardown
    DisposableEffect(lifecycleOwner, mapView) {
        var isDestroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    if (!isDestroyed) {
                        isDestroyed = true
                        isDisposed = true
                        styleOwner.invalidate()
                        mapView.onDestroy()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            isDisposed = true
            styleOwner.invalidate()
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!isDestroyed) {
                isDestroyed = true
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    fun rebuildFleetMarkers(map: MapLibreMap) {
        map.clear()
        deviceKeyByMarkerId.clear()
        currentMarkers.forEach { device ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(device.latitude, device.longitude))
                    .title(device.deviceName)
                    .snippet("${if (device.isOnline) "Online" else "Offline"} · ⚡${device.battery}%")
            )
            deviceKeyByMarkerId[marker.id] = device.uniqueKey
        }
    }

    fun frameFleetBounds(map: MapLibreMap, animate: Boolean = true) {
        if (validMarkers.isEmpty()) return
        if (validMarkers.size == 1) {
            val single = validMarkers.first()
            val update = CameraUpdateFactory.newLatLngZoom(LatLng(single.latitude, single.longitude), 14.0)
            if (animate) map.animateCamera(update) else map.moveCamera(update)
        } else {
            val boundsBuilder = LatLngBounds.Builder()
            validMarkers.forEach {
                boundsBuilder.include(LatLng(it.latitude, it.longitude))
            }
            val bounds = boundsBuilder.build()
            val update = CameraUpdateFactory.newLatLngBounds(bounds, 64)
            if (animate) map.animateCamera(update) else map.moveCamera(update)
        }
    }

    // Single explicit style request owner driven by retryGeneration
    LaunchedEffect(maplibreMapInstance, config.styleUrl, retryGeneration) {
        val map = maplibreMapInstance ?: return@LaunchedEffect
        val attempt = styleOwner.begin()
        styleState = MapStyleState.Loading

        map.setStyle(config.styleUrl) { style ->
            if (styleOwner.finish(attempt) && !isDisposed) {
                styleState = MapStyleState.Ready
                rebuildFleetMarkers(map)
                if (validMarkers.size >= 2) {
                    updateRoutePolyline(style, validMarkers.map { it.latitude to it.longitude })
                }
                if (cameraInitializationComplete && isFollowing) {
                    frameFleetBounds(map, animate = false)
                }
            }
        }

        // Bounded 12s timeout tied to attempt
        delay(12_000)
        if (styleOwner.isCurrent(attempt) && !isDisposed && styleState is MapStyleState.Loading) {
            if (styleOwner.finish(attempt)) {
                styleState = MapStyleState.Error(lastAdvisoryError ?: "Tile request timed out. Please check network connection.")
            }
        }
    }

    // Update annotations when markers change
    LaunchedEffect(validMarkers, maplibreMapInstance, styleState) {
        val map = maplibreMapInstance ?: return@LaunchedEffect
        if (styleState is MapStyleState.Ready) {
            rebuildFleetMarkers(map)
            map.getStyle { style ->
                if (validMarkers.size >= 2) {
                    updateRoutePolyline(style, validMarkers.map { it.latitude to it.longitude })
                } else {
                    updateRoutePolyline(style, emptyList())
                }
            }
            if (cameraInitializationComplete && isFollowing) {
                frameFleetBounds(map, animate = true)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, FigmaCardBorder, RoundedCornerShape(20.dp))
    ) {
        AndroidView(
            factory = {
                mapView.apply {
                    val failListener = MapView.OnDidFailLoadingMapListener { error ->
                        // MapLibre onDidFailLoadingMap is map-level without request correlation.
                        // Kept as advisory diagnostic state per Pass 5 Work Order 5.
                        lastAdvisoryError = error ?: "Failed to load fleet map"
                    }
                    addOnDidFailLoadingMapListener(failListener)

                    getMapAsync { map ->
                        if (isDisposed) return@getMapAsync
                        maplibreMapInstance = map

                        map.uiSettings.isCompassEnabled = false
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isLogoEnabled = false

                        // User gesture pan/zoom cancels follow mode
                        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                            override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                                isFollowing = false
                            }
                            override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                            override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                        })

                        // Real-time continuous compass updates during camera movement
                        map.addOnCameraMoveListener {
                            currentBearing = map.cameraPosition.bearing
                        }

                        // Capture settled camera snapshot
                        map.addOnCameraIdleListener {
                            val pos = map.cameraPosition
                            val target = pos.target
                            val fallbackLat = validMarkers.firstOrNull()?.latitude ?: 0.0
                            val fallbackLon = validMarkers.firstOrNull()?.longitude ?: 0.0
                            currentBearing = pos.bearing
                            savedCamera = CameraSnapshot(
                                latitude = target?.latitude ?: fallbackLat,
                                longitude = target?.longitude ?: fallbackLon,
                                zoom = pos.zoom,
                                bearing = pos.bearing,
                                tilt = pos.tilt,
                                following = isFollowing,
                                isRestored = true
                            )
                        }

                        // Native marker click mapped strictly to stable device key
                        map.setOnMarkerClickListener { marker ->
                            deviceKeyByMarkerId[marker.id]?.let(currentOnMarkerClick)
                            true
                        }

                        // Initial restore or frame
                        if (savedCamera.isRestored) {
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(savedCamera.latitude, savedCamera.longitude))
                                .zoom(savedCamera.zoom)
                                .bearing(savedCamera.bearing)
                                .tilt(savedCamera.tilt)
                                .build()
                            currentBearing = savedCamera.bearing
                            isFollowing = savedCamera.following
                        } else {
                            frameFleetBounds(map, animate = false)
                        }
                        cameraInitializationComplete = true
                    }
                }
            },
            update = {},
            modifier = Modifier.fillMaxSize()
        )

        // Loading overlay
        if (styleState is MapStyleState.Loading) {
            MapLoadingOverlay(text = "Loading fleet map tiles…")
        }

        // Style Error state with retry
        if (styleState is MapStyleState.Error) {
            val errorMsg = (styleState as MapStyleState.Error).message
            MapErrorOverlay(
                message = errorMsg,
                onRetry = {
                    retryGeneration++
                }
            )
        }

        // Top-right vertical control rail: Compass + Fit Fleet
        if (styleState is MapStyleState.Ready) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Compass control slot
                CompassControl(
                    bearing = currentBearing,
                    onResetNorth = {
                        maplibreMapInstance?.let { map ->
                            val cur = map.cameraPosition
                            map.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder(cur).bearing(0.0).build()
                                ),
                                350
                            )
                        }
                    }
                )

                // Fit fleet control slot
                RecenterControl(
                    isFollowing = isFollowing,
                    isFleet = true,
                    onClick = {
                        isFollowing = true
                        maplibreMapInstance?.let { frameFleetBounds(it, animate = true) }
                    }
                )
            }
        }
    }
}

/**
 * Accessible custom compass control pointing North based on camera bearing.
 * Tapping smoothly eases map camera bearing back to true North.
 */
@Composable
fun CompassControl(
    bearing: Double,
    onResetNorth: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onResetNorth)
            .semantics {
                role = Role.Button
                contentDescription = "Compass pointing North. Tap to reset orientation to North"
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xEE141C24))
                .border(1.dp, FigmaCardBorder, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Navigation,
                contentDescription = null,
                tint = FigmaAccentBlue,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        // Invert bearing so arrow points continuously toward true North
                        rotationZ = -bearing.toFloat()
                    }
            )
        }
    }
}

/**
 * Accessible 48dp recenter control with DarkOnPrimary contrast on filled state.
 */
@Composable
fun RecenterControl(
    isFollowing: Boolean,
    isFleet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = if (isFleet) {
                    if (isFollowing) "Fleet tracking active" else "Fit fleet on map"
                } else {
                    if (isFollowing) "Tracking device location" else "Center device on map"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isFollowing) FigmaAccentBlue else Color(0xEE141C24))
                .border(1.dp, if (isFollowing) FigmaAccentBlue else FigmaCardBorder, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = null,
                tint = if (isFollowing) Color(0xFF080C14) /* Dark on primary for contrast */ else FigmaTextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MapLoadingOverlay(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9080C14)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = FigmaAccentBlue,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                color = FigmaTextMuted
            )
        }
    }
}

@Composable
private fun MapErrorOverlay(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0080C14))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Map Tile Loading Failed",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = FigmaTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                color = FigmaTextMuted
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FigmaAccentBlue.copy(alpha = 0.2f))
                    .clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = FigmaAccentBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Retry",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaAccentBlue
                )
            }
        }
    }
}

@Composable
private fun InvalidCoordinatesCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, FigmaCardBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.LocationOff,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = FigmaTextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = FigmaTextMuted
                )
            }
        }
    }
}

private const val ROUTE_SOURCE_ID = "sentinel-route-source"
private const val ROUTE_LAYER_ID = "sentinel-route-layer"
private const val INTERCEPT_SOURCE_ID = "sentinel-intercept-source"
private const val INTERCEPT_LAYER_ID = "sentinel-intercept-layer"

private fun updateRoutePolyline(style: org.maplibre.android.maps.Style?, coords: List<Pair<Double, Double>>) {
    if (style == null || !style.isFullyLoaded) return
    try {
        if (coords.size < 2) {
            if (style.getLayer(ROUTE_LAYER_ID) != null) {
                style.removeLayer(ROUTE_LAYER_ID)
            }
            if (style.getSource(ROUTE_SOURCE_ID) != null) {
                style.removeSource(ROUTE_SOURCE_ID)
            }
            return
        }

        val points = coords.map { (lat, lon) ->
            Point.fromLngLat(lon, lat)
        }
        val lineString = LineString.fromLngLats(points)

        val existingSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        if (existingSource != null) {
            existingSource.setGeoJson(lineString)
        } else {
            val source = GeoJsonSource(ROUTE_SOURCE_ID, lineString)
            style.addSource(source)
        }

        if (style.getLayer(ROUTE_LAYER_ID) == null) {
            val lineLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.lineColor(android.graphics.Color.parseColor("#38BDF8")),
                    PropertyFactory.lineWidth(3.5f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            }
            style.addLayer(lineLayer)
        }
    } catch (e: Exception) {
        android.util.Log.w("Sentinel:MapLibre", "Failed to render route polyline: ${e.message}")
    }
}

private fun updateInterceptVector(
    style: org.maplibre.android.maps.Style?,
    currentLat: Double,
    currentLon: Double,
    interceptPoint: Pair<Double, Double>?
) {
    if (style == null || !style.isFullyLoaded) return
    try {
        if (interceptPoint == null || !isValidCoordinate(interceptPoint.first, interceptPoint.second)) {
            if (style.getLayer(INTERCEPT_LAYER_ID) != null) {
                style.removeLayer(INTERCEPT_LAYER_ID)
            }
            if (style.getSource(INTERCEPT_SOURCE_ID) != null) {
                style.removeSource(INTERCEPT_SOURCE_ID)
            }
            return
        }

        val points = listOf(
            Point.fromLngLat(currentLon, currentLat),
            Point.fromLngLat(interceptPoint.second, interceptPoint.first)
        )
        val lineString = LineString.fromLngLats(points)

        val existingSource = style.getSourceAs<GeoJsonSource>(INTERCEPT_SOURCE_ID)
        if (existingSource != null) {
            existingSource.setGeoJson(lineString)
        } else {
            val source = GeoJsonSource(INTERCEPT_SOURCE_ID, lineString)
            style.addSource(source)
        }

        if (style.getLayer(INTERCEPT_LAYER_ID) == null) {
            val lineLayer = LineLayer(INTERCEPT_LAYER_ID, INTERCEPT_SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.lineColor(android.graphics.Color.parseColor("#F59E0B")),
                    PropertyFactory.lineWidth(3.0f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineDasharray(arrayOf(3f, 3f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            }
            style.addLayer(lineLayer)
        }
    } catch (e: Exception) {
        android.util.Log.w("Sentinel:MapLibre", "Failed to render intercept vector: ${e.message}")
    }
}
