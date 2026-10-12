package com.sentinel.admin.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.sentinel.admin.domain.model.BreadcrumbPoint
import com.sentinel.admin.domain.model.DwellCluster

@Composable
fun HistoricalRouteMap(
    points: List<BreadcrumbPoint>,
    dwellSpots: List<DwellCluster>,
    selectedTimestamp: Long,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    val initialPosition = remember(points) {
        LatLng(points.first().latitude, points.first().longitude)
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialPosition, 14f)
    }

    // Find the historical point matching the current scrubber timestamp
    val activePoint = remember(points, selectedTimestamp) {
        points.minByOrNull { kotlin.math.abs(it.timestamp - selectedTimestamp) } ?: points.last()
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        uiSettings = MapUiSettings(zoomControlsEnabled = false)
    ) {
        // Render speed-colored polyline segments
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            val segmentColor = when {
                p1.speedKmh > 80f -> Color.Red         // Speed violation
                p1.speedKmh > 40f -> Color(0xFFFFA000) // Moderate speed (Amber)
                else -> Color(0xFF2E7D32)              // Normal speed (Green)
            }

            Polyline(
                points = listOf(
                    LatLng(p1.latitude, p1.longitude),
                    LatLng(p2.latitude, p2.longitude)
                ),
                color = segmentColor,
                width = 10f
            )
        }

        // Render Dwell Point markers (locations where device stopped)
        dwellSpots.forEach { dwell ->
            val dwellMarkerState = remember(dwell.latitude, dwell.longitude) {
                MarkerState(position = LatLng(dwell.latitude, dwell.longitude))
            }
            Marker(
                state = dwellMarkerState,
                title = "Stopped for ${dwell.durationMinutes} min",
                snippet = "From ${dwell.startTime} to ${dwell.endTime}",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
            )
        }

        // Active Historical Avatar Marker at current scrubber position
        val activeMarkerState = remember(activePoint.latitude, activePoint.longitude) {
            MarkerState(position = LatLng(activePoint.latitude, activePoint.longitude))
        }
        Marker(
            state = activeMarkerState,
            title = "Speed: ${activePoint.speedKmh.toInt()} km/h",
            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)
        )

        Circle(
            center = LatLng(activePoint.latitude, activePoint.longitude),
            radius = activePoint.accuracy.toDouble().coerceAtLeast(10.0),
            fillColor = Color(0x337B1FA2),
            strokeColor = Color(0xAA7B1FA2),
            strokeWidth = 2f
        )
    }
}
