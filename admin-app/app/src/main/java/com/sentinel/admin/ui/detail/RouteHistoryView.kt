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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.ui.map.GeofenceDraftEditor
import com.sentinel.admin.ui.map.MapsLauncher
import com.sentinel.admin.ui.map.NativeMapLibreView
import com.sentinel.admin.ui.map.isValidCoordinate
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaAccentCoral
import com.sentinel.admin.ui.theme.FigmaButtonSurface
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaScreenBackground
import com.sentinel.admin.ui.theme.FigmaTabSelected
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Supported route date filter modes.
 */
enum class RouteDateFilter(val label: String) {
    TODAY("Today"),
    SEVEN_DAYS("7 days"),
    CUSTOM("Custom")
}

/**
 * Date range validation result for custom route queries.
 */
sealed interface DateValidationResult {
    data class Success(val startDate: LocalDate, val endDate: LocalDate) : DateValidationResult
    data class Error(val message: String) : DateValidationResult
}

/**
 * Calculator and validator for route date ranges with explicit timezone and inclusive boundaries.
 */
internal object RouteDateRangeCalculator {
    fun getTodayRange(zoneId: ZoneId = ZoneId.systemDefault()): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(zoneId)
        return today to today
    }

    fun getSevenDaysRange(zoneId: ZoneId = ZoneId.systemDefault()): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(zoneId)
        return today.minusDays(6) to today // 7 inclusive days (today - 6 through today)
    }

    fun validateRange(
        startDateStr: String,
        endDateStr: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): DateValidationResult {
        val today = LocalDate.now(zoneId)
        val trimmedStart = startDateStr.trim()
        val trimmedEnd = endDateStr.trim()

        if (trimmedStart.isBlank()) {
            return DateValidationResult.Error("Start date cannot be empty")
        }
        if (trimmedEnd.isBlank()) {
            return DateValidationResult.Error("End date cannot be empty")
        }

        val startDate = try {
            LocalDate.parse(trimmedStart, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            return DateValidationResult.Error("Invalid start date format. Expected YYYY-MM-DD")
        }

        val endDate = try {
            LocalDate.parse(trimmedEnd, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            return DateValidationResult.Error("Invalid end date format. Expected YYYY-MM-DD")
        }

        if (startDate.isAfter(today)) {
            return DateValidationResult.Error("Start date cannot be in the future (today is $today)")
        }
        if (endDate.isAfter(today)) {
            return DateValidationResult.Error("End date cannot be in the future (today is $today)")
        }
        if (startDate.isAfter(endDate)) {
            return DateValidationResult.Error("Start date cannot be after end date")
        }

        return DateValidationResult.Success(startDate, endDate)
    }
}

/**
 * Route History tab view matching Figma concept:
 * "Concept / 03 / Historical route".
 *
 * Implements:
 * - Scoped date filter state per device, preserved across rotation and tab changes.
 * - Themed date-range picker dialog with editable fields, validation, Apply and Cancel.
 * - Cancel preserves previously applied range.
 * - Explicit local timezone and inclusive boundary explanation (00:00:00 to 23:59:59).
 * - Truthful representation: Backend and Host only stream single-point real-time location.
 * - Historical breadcrumb route playback disabled with clear dependency notice.
 * - Displays real-time location telemetry truthfully without presenting it as a historical query result.
 * - Minimum 48dp touch targets with accessibility semantics.
 */
@Composable
fun RouteHistoryView(
    deviceKey: String,
    deviceName: String,
    location: DeviceLocation?,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    routePoints: List<DeviceLocation> = emptyList(),
    onDateRangeChanged: (String, String) -> Unit = { _, _ -> },
    geofenceStateHolder: com.sentinel.admin.ui.map.GeofenceEditorStateHolder? = null,
    onDeployGeofence: (Double, Double, Float) -> Unit = { _, _, _ -> }
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val zoneDisplayName = remember {
        val offset = zoneId.rules.getOffset(java.time.Instant.now())
        "${zoneId.id} ($offset)"
    }

    // Scoped filter selection to this deviceKey: preserved across rotation and tab changes
    var activeFilterName by rememberSaveable(
        inputs = arrayOf(deviceKey),
        key = "route_active_filter_$deviceKey"
    ) {
        mutableStateOf(RouteDateFilter.TODAY.name)
    }

    val todayRange = remember(zoneId) { RouteDateRangeCalculator.getTodayRange(zoneId) }

    // Applied date boundaries (inclusive ISO dates)
    var appliedStartDate by rememberSaveable(
        inputs = arrayOf(deviceKey),
        key = "route_applied_start_$deviceKey"
    ) {
        mutableStateOf(todayRange.first.toString())
    }

    var appliedEndDate by rememberSaveable(
        inputs = arrayOf(deviceKey),
        key = "route_applied_end_$deviceKey"
    ) {
        mutableStateOf(todayRange.second.toString())
    }

    LaunchedEffect(appliedStartDate, appliedEndDate) {
        onDateRangeChanged(appliedStartDate, appliedEndDate)
    }

    // Custom date range dialog visibility scoped to device
    var showCustomDialog by rememberSaveable(
        inputs = arrayOf(deviceKey),
        key = "route_show_custom_dialog_$deviceKey"
    ) {
        mutableStateOf(false)
    }

    var mapsFeedbackMessage by remember { mutableStateOf<String?>(null) }
    val filters = RouteDateFilter.entries

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Time filter pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { filter ->
                val isSelected = activeFilterName == filter.name
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
                        .clickable {
                            when (filter) {
                                RouteDateFilter.TODAY -> {
                                    val (start, end) = RouteDateRangeCalculator.getTodayRange(zoneId)
                                    appliedStartDate = start.toString()
                                    appliedEndDate = end.toString()
                                    activeFilterName = RouteDateFilter.TODAY.name
                                    onDateRangeChanged(appliedStartDate, appliedEndDate)
                                }
                                RouteDateFilter.SEVEN_DAYS -> {
                                    val (start, end) = RouteDateRangeCalculator.getSevenDaysRange(zoneId)
                                    appliedStartDate = start.toString()
                                    appliedEndDate = end.toString()
                                    activeFilterName = RouteDateFilter.SEVEN_DAYS.name
                                    onDateRangeChanged(appliedStartDate, appliedEndDate)
                                }
                                RouteDateFilter.CUSTOM -> {
                                    showCustomDialog = true
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics {
                            role = Role.Tab
                            contentDescription = "Filter route by ${filter.label}"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filter.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) FigmaAccentBlue else FigmaTextMuted
                    )
                }
            }
        }

        // Active Date Range Display Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
            border = BorderStroke(1.dp, FigmaCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = FigmaAccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (activeFilterName) {
                                RouteDateFilter.TODAY.name -> "TODAY"
                                RouteDateFilter.SEVEN_DAYS.name -> "LAST 7 DAYS"
                                else -> "CUSTOM RANGE"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = FigmaAccentBlue,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Text(
                        text = "$appliedStartDate  →  $appliedEndDate",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = FigmaTextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Boundaries: $appliedStartDate 00:00:00 to $appliedEndDate 23:59:59 · $zoneDisplayName (inclusive)",
                    fontSize = 11.sp,
                    color = FigmaTextMuted
                )
            }
        }

        // Route Dependency / Active Notice
        if (routePoints.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF14241B))
                    .border(1.dp, Color(0xFF1E462E), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF4ADE80),
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Historical Route Replay Active",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4ADE80)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Displaying ${routePoints.size} recorded breadcrumbs between $appliedStartDate and $appliedEndDate. Use the scrubber slider below to step through waypoints.",
                        fontSize = 11.sp,
                        color = FigmaTextMuted,
                        lineHeight = 15.sp
                    )
                }
            }
        } else {
            // Truthful Historical Route Dependency Notice
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141E28))
                    .border(1.dp, Color(0xFF1F2B3E), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = FigmaAccentBlue,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Historical Route Retrieval Unavailable",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = FigmaAccentBlue
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "History fetching is disabled: Backend and host device currently only stream live location telemetry. Database breadcrumb storage and route replay endpoints are not yet implemented. The map below truthfully displays the live fix, not historical route data for $appliedStartDate to $appliedEndDate.",
                        fontSize = 11.sp,
                        color = FigmaTextMuted,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        var scrubberIndex by rememberSaveable(deviceKey, routePoints.size) {
            mutableFloatStateOf((routePoints.size - 1).coerceAtLeast(0).toFloat())
        }
        var isPlayingRoute by rememberSaveable(deviceKey, routePoints.size) { mutableStateOf(false) }
        var playbackSpeedMultiplier by rememberSaveable(deviceKey) { mutableStateOf(1) }

        LaunchedEffect(isPlayingRoute, playbackSpeedMultiplier, routePoints.size) {
            if (isPlayingRoute && routePoints.size > 1) {
                if (scrubberIndex.toInt() >= routePoints.size - 1) {
                    scrubberIndex = 0f
                }
                while (isPlayingRoute) {
                    kotlinx.coroutines.delay((600L / playbackSpeedMultiplier).coerceAtLeast(100L))
                    val nextIdx = scrubberIndex.toInt() + 1
                    if (nextIdx < routePoints.size) {
                        scrubberIndex = nextIdx.toFloat()
                    } else {
                        isPlayingRoute = false
                        break
                    }
                }
            }
        }

        val currentIndex = if (routePoints.isNotEmpty()) {
            scrubberIndex.toInt().coerceIn(0, routePoints.size - 1)
        } else 0
        val currentRoutePoint = if (routePoints.isNotEmpty()) routePoints[currentIndex] else null

        val hasFix = location != null && location.hasReportedFix && isValidCoordinate(location.latitude, location.longitude)
        val hasValidDisplayPoint = if (routePoints.isNotEmpty()) {
            currentRoutePoint != null && isValidCoordinate(currentRoutePoint.latitude, currentRoutePoint.longitude)
        } else {
            hasFix
        }

        val displayLat = if (routePoints.isNotEmpty()) currentRoutePoint?.latitude else (if (hasFix) location?.latitude else null)
        val displayLon = if (routePoints.isNotEmpty()) currentRoutePoint?.longitude else (if (hasFix) location?.longitude else null)
        val displayAccuracy = if (routePoints.isNotEmpty()) (currentRoutePoint?.accuracy?.toFloat() ?: 10f) else (if (hasFix) (location?.accuracy?.toFloat() ?: 10f) else 0f)

        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isCompact = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE || configuration.screenHeightDp < 550

        val routeCoordinates = remember(routePoints) {
            routePoints
                .filter { isValidCoordinate(it.latitude, it.longitude) }
                .map { it.latitude to it.longitude }
        }

        // Native MapLibre vector tile location map (shows live fix or selected route waypoint)
        NativeMapLibreView(
            latitude = if (hasValidDisplayPoint) displayLat else null,
            longitude = if (hasValidDisplayPoint) displayLon else null,
            accuracyMeters = if (hasValidDisplayPoint) displayAccuracy else 0f,
            deviceName = deviceName,
            isOnline = isOnline,
            routeCoordinates = routeCoordinates,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isCompact) 220.dp else 260.dp)
        )

        // "Open in Maps" action for exact coordinates
        val context = androidx.compose.ui.platform.LocalContext.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasValidDisplayPoint && displayLat != null && displayLon != null) {
                    val latStr = String.format(java.util.Locale.US, "%.5f", displayLat)
                    val lonStr = String.format(java.util.Locale.US, "%.5f", displayLon)
                    val accStr = if (displayAccuracy > 0) " · ±${displayAccuracy.toInt()}m" else ""
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
                    .background(if (hasValidDisplayPoint) FigmaButtonSurface else FigmaButtonSurface.copy(alpha = 0.4f))
                    .border(1.dp, FigmaCardBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = hasValidDisplayPoint) {
                        when (MapsLauncher.openDevicePoint(
                            context = context,
                            latitude = displayLat,
                            longitude = displayLon,
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
                        tint = if (hasValidDisplayPoint) FigmaAccentBlue else FigmaTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open in Maps",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasValidDisplayPoint) FigmaTextPrimary else FigmaTextMuted
                    )
                }
            }
        }

        mapsFeedbackMessage?.let { msg ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = FigmaAccentCoral,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Route Scrubber Replay Slider
        if (routePoints.isNotEmpty()) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ROUTE PLAYBACK (${currentIndex + 1}/${routePoints.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = FigmaAccentBlue,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = currentRoutePoint?.recordedAt?.takeIf { it.isNotBlank() }?.let { formatSampleTime(it) } ?: "",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = FigmaTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Slider(
                        value = scrubberIndex.coerceIn(0f, (routePoints.size - 1).coerceAtLeast(1).toFloat()),
                        onValueChange = {
                            isPlayingRoute = false
                            scrubberIndex = it
                        },
                        valueRange = 0f..(routePoints.size - 1).coerceAtLeast(1).toFloat(),
                        steps = if (routePoints.size > 2) routePoints.size - 2 else 0,
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
                        Text(
                            text = "1 · ${formatSampleTime(routePoints.first().recordedAt)}",
                            fontSize = 11.sp,
                            color = FigmaTextMuted
                        )
                        Text(
                            text = "${routePoints.size} · ${formatSampleTime(routePoints.last().recordedAt)}",
                            fontSize = 11.sp,
                            color = FigmaTextMuted
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tactical Play / Pause & Speed Multiplier Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play / Pause pill button
                        Box(
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isPlayingRoute) FigmaAccentBlue else FigmaButtonSurface)
                                .border(
                                    1.dp,
                                    if (isPlayingRoute) FigmaAccentBlue else FigmaCardBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { isPlayingRoute = !isPlayingRoute }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = if (isPlayingRoute) "Pause route replay" else "Play route replay"
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isPlayingRoute) "⏸ PAUSE" else "▶ PLAY",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPlayingRoute) Color.White else FigmaTextPrimary
                            )
                        }

                        // Speed Multiplier Pills (1x, 2x, 4x)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(1, 2, 4).forEach { speed ->
                                val isSelected = playbackSpeedMultiplier == speed
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) FigmaTabSelected else FigmaButtonSurface)
                                        .border(
                                            1.dp,
                                            if (isSelected) FigmaAccentBlue else FigmaCardBorder,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { playbackSpeedMultiplier = speed }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .semantics {
                                            role = Role.Tab
                                            contentDescription = "Set replay speed to ${speed}x"
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${speed}x",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) FigmaAccentBlue else FigmaTextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Truthful Location Status Card
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
                if (routePoints.isNotEmpty() && currentRoutePoint != null) {
                    val sampleTime = currentRoutePoint.recordedAt.takeIf { it.isNotBlank() }
                    val displayedTime = if (sampleTime != null) formatSampleTime(sampleTime) else "Waypoint #${currentIndex + 1}"

                    Text(
                        text = displayedTime,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = FigmaTextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val accStr = if (currentRoutePoint.accuracy > 0) "Accuracy ±${currentRoutePoint.accuracy.toInt()}m · " else ""
                    val battStr = if (currentRoutePoint.battery > 0) "Battery ${currentRoutePoint.battery}% · " else ""
                    val netStr = if (currentRoutePoint.network.isNotBlank()) "${currentRoutePoint.network} · " else ""
                    Text(
                        text = "${accStr}${battStr}${netStr}Waypoint ${currentIndex + 1} of ${routePoints.size} (historical)",
                        fontSize = 13.sp,
                        color = FigmaTextMuted
                    )
                } else if (hasFix) {
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
                    val statusText = if (isOnline) "Live fix (online) · Not a historical route result" else "Live fix (offline) · Not a historical route result"
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

                if (routePoints.isNotEmpty()) {
                    val totalDistanceKm = calculateTotalDistanceKm(routePoints)
                    Text(
                        text = String.format(java.util.Locale.US, "Total distance: %.2f km across %d GPS waypoints for %s to %s", totalDistanceKm, routePoints.size, appliedStartDate, appliedEndDate),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = FigmaTextPrimary
                    )

                    val dwellZones = remember(routePoints) { detectDwellClusters(routePoints) }
                    if (dwellZones.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "PATTERN-OF-LIFE SAFE-HOUSE / DWELL CLUSTERS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = FigmaAccentBlue,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        dwellZones.forEach { dwell ->
                            Text(
                                text = "📍 $dwell",
                                fontSize = 12.sp,
                                color = FigmaTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Trip summary unavailable for $appliedStartDate to $appliedEndDate · Server does not store breadcrumbs or calculate historical trips.",
                        fontSize = 13.sp,
                        color = FigmaTextMuted
                    )
                }
            }
        }

        // Native Geofence Draft Editor Milestone
        GeofenceDraftEditor(
            deviceId = deviceKey,
            initialLat = if (hasValidDisplayPoint) displayLat else null,
            initialLon = if (hasValidDisplayPoint) displayLon else null,
            stateHolder = geofenceStateHolder,
            onDraftSaved = { draft ->
                if (draft is com.sentinel.admin.ui.map.GeofenceDraft.Circle) {
                    onDeployGeofence(draft.centerLat, draft.centerLon, draft.radiusMeters)
                }
            }
        )
    }

    // Themed Custom Date Range Dialog
    if (showCustomDialog) {
        ThemedDateRangePickerDialog(
            initialStartDate = appliedStartDate,
            initialEndDate = appliedEndDate,
            zoneDisplayName = zoneDisplayName,
            zoneId = zoneId,
            onApply = { start, end ->
                appliedStartDate = start.toString()
                appliedEndDate = end.toString()
                activeFilterName = RouteDateFilter.CUSTOM.name
                showCustomDialog = false
                onDateRangeChanged(appliedStartDate, appliedEndDate)
            },
            onDismiss = {
                // Cancel preserves previously applied range
                showCustomDialog = false
            }
        )
    }
}

/**
 * Themed dark date-range picker dialog matching Figma design system tokens.
 */
@Composable
private fun ThemedDateRangePickerDialog(
    initialStartDate: String,
    initialEndDate: String,
    zoneDisplayName: String,
    zoneId: ZoneId,
    onApply: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var draftStartDate by remember { mutableStateOf(initialStartDate) }
    var draftEndDate by remember { mutableStateOf(initialEndDate) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val today = remember { LocalDate.now(zoneId) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
            border = BorderStroke(1.dp, FigmaCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = FigmaAccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Custom Date Range",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = FigmaTextPrimary
                    )
                }

                Text(
                    text = "Timezone: $zoneDisplayName\nInclusive boundary: 00:00:00 (start) to 23:59:59 (end)",
                    fontSize = 12.sp,
                    color = FigmaTextMuted,
                    lineHeight = 16.sp
                )

                // Quick Presets Row
                Text(
                    text = "QUICK PRESETS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaAccentBlue,
                    letterSpacing = 0.5.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PresetChip("Today", onClick = {
                        draftStartDate = today.toString()
                        draftEndDate = today.toString()
                        validationError = null
                    })
                    PresetChip("Yesterday", onClick = {
                        val y = today.minusDays(1)
                        draftStartDate = y.toString()
                        draftEndDate = y.toString()
                        validationError = null
                    })
                    PresetChip("7 Days", onClick = {
                        draftStartDate = today.minusDays(6).toString()
                        draftEndDate = today.toString()
                        validationError = null
                    })
                    PresetChip("30 Days", onClick = {
                        draftStartDate = today.minusDays(29).toString()
                        draftEndDate = today.toString()
                        validationError = null
                    })
                }

                // Start Date Input
                OutlinedTextField(
                    value = draftStartDate,
                    onValueChange = {
                        draftStartDate = it
                        validationError = null
                    },
                    label = { Text("Start Date (YYYY-MM-DD)", fontSize = 12.sp) },
                    placeholder = { Text(today.toString(), color = FigmaTextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = FigmaTextPrimary,
                        unfocusedTextColor = FigmaTextPrimary,
                        focusedBorderColor = FigmaAccentBlue,
                        unfocusedBorderColor = FigmaCardBorder,
                        focusedLabelColor = FigmaAccentBlue,
                        unfocusedLabelColor = FigmaTextMuted,
                        cursorColor = FigmaAccentBlue,
                        focusedContainerColor = FigmaButtonSurface,
                        unfocusedContainerColor = FigmaButtonSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // End Date Input
                OutlinedTextField(
                    value = draftEndDate,
                    onValueChange = {
                        draftEndDate = it
                        validationError = null
                    },
                    label = { Text("End Date (YYYY-MM-DD)", fontSize = 12.sp) },
                    placeholder = { Text(today.toString(), color = FigmaTextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = FigmaTextPrimary,
                        unfocusedTextColor = FigmaTextPrimary,
                        focusedBorderColor = FigmaAccentBlue,
                        unfocusedBorderColor = FigmaCardBorder,
                        focusedLabelColor = FigmaAccentBlue,
                        unfocusedLabelColor = FigmaTextMuted,
                        cursorColor = FigmaAccentBlue,
                        focusedContainerColor = FigmaButtonSurface,
                        unfocusedContainerColor = FigmaButtonSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Validation Error display
                if (validationError != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(FigmaAccentCoral.copy(alpha = 0.15f))
                            .border(1.dp, FigmaAccentCoral.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = FigmaAccentCoral,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = validationError!!,
                            fontSize = 12.sp,
                            color = FigmaAccentCoral,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Action Buttons (Cancel preserves range, Apply validates and commits)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FigmaButtonSurface,
                            contentColor = FigmaTextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Cancel custom date range selection"
                            }
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            when (val result = RouteDateRangeCalculator.validateRange(draftStartDate, draftEndDate, zoneId)) {
                                is DateValidationResult.Success -> {
                                    onApply(result.startDate, result.endDate)
                                }
                                is DateValidationResult.Error -> {
                                    validationError = result.message
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FigmaAccentBlue,
                            contentColor = Color(0xFF0B0F14)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Apply custom date range"
                            }
                    ) {
                        Text(
                            text = "Apply",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact helper chip for quick range presets in dialog.
 */
@Composable
private fun PresetChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FigmaButtonSurface)
            .border(1.dp, FigmaCardBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Set date range to $label"
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = FigmaAccentBlue
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
                instant.atZone(ZoneId.systemDefault())
            }
            raw.contains("T") -> {
                try {
                    java.time.OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault())
                } catch (_: Exception) {
                    java.time.Instant.parse(raw).atZone(ZoneId.systemDefault())
                }
            }
            else -> null
        }
        if (zdt != null) {
            DateTimeFormatter.ofPattern("HH:mm:ss (MMM d)").format(zdt)
        } else {
            raw.substringAfter("T").take(8).ifBlank { raw }
        }
    } catch (_: Exception) {
        raw.substringAfter("T").take(8).ifBlank { raw }
    }
}

/**
 * Calculates total cumulative Haversine distance in kilometers across recorded waypoints.
 */
internal fun calculateTotalDistanceKm(points: List<DeviceLocation>): Double {
    if (points.size < 2) return 0.0
    var totalDistance = 0.0
    for (i in 0 until points.size - 1) {
        val p1 = points[i]
        val p2 = points[i + 1]
        totalDistance += haversineDistanceKm(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }
    return totalDistance
}

internal fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0 // Earth radius in km
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}

internal fun detectDwellClusters(points: List<DeviceLocation>): List<String> {
    if (points.size < 3) return emptyList()

    val clusters = mutableListOf<String>()
    var i = 0

    while (i < points.size && clusters.size < 4) {
        val p0 = points[i]
        var j = i + 1
        var sumLat = p0.latitude
        var sumLon = p0.longitude
        var count = 1

        while (j < points.size) {
            val pj = points[j]
            val meanLat = sumLat / count
            val meanLon = sumLon / count
            val distKm = haversineDistanceKm(meanLat, meanLon, pj.latitude, pj.longitude)
            if (distKm <= 0.04) { // 40m
                sumLat += pj.latitude
                sumLon += pj.longitude
                count++
                j++
            } else {
                break
            }
        }

        if (count >= 3) {
            val centerLat = sumLat / count
            val centerLon = sumLon / count
            val startTime = formatTimeOnly(points[i].recordedAt)
            val endTime = formatTimeOnly(points[j - 1].recordedAt)
            clusters.add(
                String.format(
                    java.util.Locale.US,
                    "Dwell Zone (%.4f, %.4f) · %d fixes (%s → %s)",
                    centerLat, centerLon, count, startTime, endTime
                )
            )
            i = j
        } else {
            i++
        }
    }

    return clusters
}

private fun formatTimeOnly(isoTimestamp: String): String {
    return try {
        val instant = java.time.Instant.parse(isoTimestamp)
        val ldt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        ldt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US))
    } catch (_: Exception) {
        isoTimestamp.takeLast(8).take(5)
    }
}
