package com.sentinel.admin.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.ui.map.GeofenceDraftEditor
import com.sentinel.admin.ui.map.MapsLauncher
import com.sentinel.admin.ui.map.NativeMapLibreView
import com.sentinel.admin.ui.map.isValidCoordinate
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaButtonSurface
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaScreenBackground
import com.sentinel.admin.ui.theme.FigmaTabSelected
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary

/**
 * Route History tab view matching Figma concept:
 * "Concept / 03 / Historical route".
 *
 * Implements Work Orders 4 & 8:
 * - Truthful representation: Backend and Host only stream single-point real-time location.
 * - Removed production 12.4 km trip summaries and 18 km/h fake scrubber values.
 * - Displays real-time location telemetry truthfully and reports the missing dependency.
 * - Interactive scrubber is isolated in a labelled test fixture preview.
 * - 48dp minimum touch targets with accessibility semantics.
 */
@Composable
fun RouteHistoryView(
    deviceKey: String,
    deviceName: String,
    location: DeviceLocation?,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    geofenceStateHolder: com.sentinel.admin.ui.map.GeofenceEditorStateHolder? = null
) {
    var selectedFilterIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var mapsFeedbackMessage by remember { mutableStateOf<String?>(null) }
    val filters = listOf("Today", "7 days", "Custom")

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Time filter pills (Work Order 8.A: quiet pill language, no checkmark)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEachIndexed { index, filter ->
                val isSelected = selectedFilterIndex == index
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) FigmaTabSelected else FigmaButtonSurface)
                        .border(
                            1.dp,
                            if (isSelected) FigmaAccentBlue else FigmaCardBorder,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedFilterIndex = index }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics {
                            role = Role.Tab
                            contentDescription = "Filter route by $filter"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filter,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) FigmaAccentBlue else FigmaTextMuted
                    )
                }
            }
        }

        val hasFix = location != null && location.hasReportedFix && isValidCoordinate(location.latitude, location.longitude)

        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isCompact = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE || configuration.screenHeightDp < 550

        // Native MapLibre vector tile location map
        NativeMapLibreView(
            latitude = if (hasFix) location?.latitude else null,
            longitude = if (hasFix) location?.longitude else null,
            accuracyMeters = if (hasFix) (location?.accuracy?.toFloat() ?: 10f) else 0f,
            deviceName = deviceName,
            isOnline = isOnline,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isCompact) 220.dp else 260.dp)
        )

        // Work Order 8.D: "Open in Maps" action for exact selected coordinates
        val context = androidx.compose.ui.platform.LocalContext.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasFix) {
                    val latStr = String.format(java.util.Locale.US, "%.5f", location!!.latitude)
                    val lonStr = String.format(java.util.Locale.US, "%.5f", location.longitude)
                    val accStr = if (location.accuracy > 0) " · ±${location.accuracy.toInt()}m" else ""
                    "$latStr, $lonStr$accStr"
                } else {
                    "Location fix unavailable"
                },
                fontSize = 12.sp,
                color = FigmaTextMuted,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (hasFix) FigmaButtonSurface else FigmaButtonSurface.copy(alpha = 0.4f))
                    .border(1.dp, FigmaCardBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = hasFix) {
                        when (MapsLauncher.openDevicePoint(
                            context = context,
                            latitude = location?.latitude,
                            longitude = location?.longitude,
                            label = deviceName
                        )) {
                            com.sentinel.admin.ui.map.MapsLaunchResult.OPENED -> {
                                mapsFeedbackMessage = null
                            }
                            com.sentinel.admin.ui.map.MapsLaunchResult.INVALID_COORDINATE -> {
                                mapsFeedbackMessage = "Location coordinates are invalid or unavailable for mapping"
                            }
                            com.sentinel.admin.ui.map.MapsLaunchResult.UNAVAILABLE -> {
                                mapsFeedbackMessage = "Unable to launch external Maps: no compatible map handler found"
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Open device location in Google Maps"
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = if (hasFix) FigmaAccentBlue else FigmaTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open in Maps",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasFix) FigmaTextPrimary else FigmaTextMuted
                    )
                }
            }
        }

        mapsFeedbackMessage?.let { msg ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = com.sentinel.admin.ui.theme.FigmaAccentCoral,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Work Order 4: Truthful Location Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
            border = BorderStroke(1.dp, FigmaCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                if (hasFix) {
                    val sampleTime = location!!.recordedAt.takeIf { it.isNotBlank() }
                    val receiptTime = location.receivedAt?.takeIf { it.isNotBlank() }

                    val displayedTime = when {
                        sampleTime != null -> formatSampleTime(sampleTime)
                        receiptTime != null -> "Received: ${formatSampleTime(receiptTime)}"
                        else -> "Sample time unknown"
                    }

                    Text(
                        text = displayedTime,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = FigmaTextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val accStr = if (location.accuracy > 0) "Accuracy ±${location.accuracy.toInt()}m · " else ""
                    val statusText = if (isOnline) "Last reported fix (online)" else "Last reported fix (offline)"
                    Text(
                        text = "$accStr$statusText",
                        fontSize = 13.sp,
                        color = FigmaTextMuted
                    )
                } else {
                    Text(
                        text = "No location fix",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = FigmaTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Device has not reported GPS coordinates",
                        fontSize = 13.sp,
                        color = FigmaTextMuted
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Missing dependency notice
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF141E28))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = FigmaAccentBlue,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Historical breadcrumb route playback unavailable: Host device only streams real-time location. Trip tracking requires backend breadcrumb storage.",
                        fontSize = 12.sp,
                        color = FigmaTextMuted,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Truthful Trip Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
            border = BorderStroke(1.dp, FigmaCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Trip summary",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Trip summary unavailable · No historical breadcrumbs recorded for this device",
                    fontSize = 13.sp,
                    color = FigmaTextMuted
                )
            }
        }

        // Work Order 10: Native Geofence Draft Editor Milestone
        GeofenceDraftEditor(
            deviceId = deviceKey,
            initialLat = if (hasFix) location?.latitude else null,
            initialLon = if (hasFix) location?.longitude else null,
            stateHolder = geofenceStateHolder
        )
    }
}

/**
 * Isolated test fixture scrubber for local UI testing and previews only.
 * Clearly separated from production data flow per Work Order 4.
 */
@Preview(name = "RouteHistoryTestFixture")
@Composable
fun RouteHistoryTestFixtureScrubber() {
    var sliderPosition by remember { mutableFloatStateOf(0.5f) }
    var isPlaying by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
        border = BorderStroke(1.dp, FigmaCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "TEST FIXTURE ONLY · Scrubbing Preview",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = FigmaAccentBlue
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "10:32:18",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = FigmaTextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                colors = SliderDefaults.colors(
                    thumbColor = FigmaAccentBlue,
                    activeTrackColor = FigmaAccentBlue,
                    inactiveTrackColor = Color(0xFF202A35)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("09:00", fontSize = 12.sp, color = FigmaTextMuted)
                Text(
                    if (isPlaying) "Pause" else "Play",
                    fontSize = 12.sp,
                    color = FigmaAccentBlue,
                    modifier = Modifier.clickable { isPlaying = !isPlaying }
                )
                Text("11:00", fontSize = 12.sp, color = FigmaTextMuted)
            }
        }
    }
}

/**
 * Formats sample time into a readable local date/time string.
 */
internal fun formatSampleTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "Sample time unknown"
    return try {
        val zdt = when {
            raw.toLongOrNull() != null -> {
                val n = raw.toLong()
                val instant = if (n < 100_000_000_000L) {
                    java.time.Instant.ofEpochSecond(n)
                } else {
                    java.time.Instant.ofEpochMilli(n)
                }
                instant.atZone(java.time.ZoneId.systemDefault())
            }
            raw.contains("T") -> {
                try {
                    java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.systemDefault())
                } catch (_: Exception) {
                    java.time.Instant.parse(raw).atZone(java.time.ZoneId.systemDefault())
                }
            }
            else -> null
        }
        if (zdt != null) {
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss (MMM d)").format(zdt)
        } else {
            raw.substringAfter("T").take(8).ifBlank { raw }
        }
    } catch (_: Exception) {
        raw.substringAfter("T").take(8).ifBlank { raw }
    }
}

