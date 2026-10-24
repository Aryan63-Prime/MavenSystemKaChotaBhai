package com.sentinel.admin.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.heightIn
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.ui.DeviceArtwork
import com.sentinel.admin.ui.FleetOrb
import com.sentinel.admin.ui.theme.FigmaAccentAmber
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaAccentGreen
import com.sentinel.admin.ui.theme.FigmaButtonSurface
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaTabSelected
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary

/**
 * Dashboard screen displaying monitored devices.
 *
 * Stateless — all state from [DashboardUiState].
 * Events via lambda callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSortOrderChanged: (SortOrder) -> Unit,
    onFleetFilterChanged: (FleetFilter) -> Unit = {},
    onViewModeChanged: (ViewMode) -> Unit,
    onDeviceClick: (String) -> Unit,
    onRecordingsClick: () -> Unit = {},
    onRetry: () -> Unit,
    onDismissEmergency: () -> Unit = {},
    onConsumeRefreshOutcome: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showIamDialog by remember { mutableStateOf(false) }
    val onlineCount = uiState.devices.count { it.heartbeatStatus == "online" }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isCompact = isLandscape || configuration.screenHeightDp < 550

    Scaffold(
        containerColor = Color(0xFF0B0F14),
        topBar = {
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Fleet",
                            fontSize = if (fontScale >= 1.3f) 20.sp else 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = FigmaTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (fontScale < 1.3f) {
                            val offlineCount = (uiState.devices.size - onlineCount).coerceAtLeast(0)
                            Text(
                                text = "$onlineCount online · $offlineCount offline",
                                fontSize = 12.sp,
                                color = FigmaTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0B0F14),
                    titleContentColor = Color(0xFFF0F2F4),
                    actionIconContentColor = Color(0xFF9AA7B6)
                ),
                actions = {
                    // Recordings Gallery
                    IconButton(onClick = onRecordingsClick) {
                        Icon(Icons.Default.Mic, contentDescription = "Saved Recordings", tint = Color(0xFF8FB2D8))
                    }
                    // Sort
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort", tint = Color(0xFF9AA7B6))
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            order.label,
                                            fontWeight = if (order == uiState.sortOrder) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                        )
                                    },
                                    onClick = {
                                        onSortOrderChanged(order)
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    // Map/List toggle
                    IconButton(onClick = {
                        val newMode = if (uiState.viewMode == ViewMode.LIST) ViewMode.MAP else ViewMode.LIST
                        onViewModeChanged(newMode)
                    }) {
                        Icon(
                            if (uiState.viewMode == ViewMode.LIST) Icons.Default.Map else Icons.Default.ViewList,
                            contentDescription = if (uiState.viewMode == ViewMode.LIST) "Map view" else "List view",
                            tint = Color(0xFFAAC7E8)
                        )
                    }
                    // Sovereign IAM & Intrusion Console
                    IconButton(onClick = { showIamDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.AdminPanelSettings,
                            contentDescription = "Sovereign IAM & Intrusion Console",
                            tint = Color(0xFF10B981)
                        )
                    }
                    // Refresh
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF9AA7B6))
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.viewMode == ViewMode.MAP) {
                Column(modifier = Modifier.fillMaxSize()) {
                    uiState.emergencyAlert?.let { alert ->
                        EmergencyAlertBanner(
                            alert = alert,
                            onSilence = onDismissEmergency,
                            onViewDevice = {
                                val targetKey = uiState.devices.find {
                                    (alert.callsign.isNotBlank() && it.resolvedCallsign.equals(alert.callsign, ignoreCase = true)) ||
                                    (alert.deviceId != "HOST-001" && it.deviceId == alert.deviceId)
                                }?.uniqueKey ?: alert.deviceId
                                onDeviceClick(targetKey)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    // Search bar
                    SearchBar(
                        query = uiState.searchQuery,
                        onQueryChanged = onSearchQueryChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = if (isCompact) 3.dp else 6.dp)
                    )

                    // Fleet Filter Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FleetFilter.entries.forEach { filter ->
                            val count = when (filter) {
                                FleetFilter.ALL -> uiState.devices.size
                                FleetFilter.ONLINE -> uiState.devices.count { it.heartbeatStatus == "online" }
                                FleetFilter.LOW_BATTERY -> uiState.devices.count {
                                    val b = it.latestLocation?.battery ?: -1
                                    b in 0..20
                                }
                                FleetFilter.EMERGENCY -> {
                                    val alertKey = uiState.emergencyAlert?.deviceId
                                    if (alertKey != null) {
                                        uiState.devices.count { it.deviceId == alertKey || it.uniqueKey == alertKey || it.connectionId == alertKey }
                                    } else 0
                                }
                            }
                            val isSelected = uiState.fleetFilter == filter
                            val tint by androidx.compose.animation.animateColorAsState(
                                targetValue = if (isSelected) FigmaAccentBlue else FigmaTextMuted,
                                animationSpec = androidx.compose.animation.core.tween(160),
                                label = "filterTint"
                            )
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
                                    .clickable { onFleetFilterChanged(filter) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                    .semantics {
                                        role = Role.Tab
                                        contentDescription = "Filter fleet by ${filter.label}, $count devices"
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = filter.label,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = tint
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) FigmaAccentBlue.copy(alpha = 0.2f) else Color(0x33FFFFFF))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "$count",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = tint
                                        )
                                    }
                                }
                            }
                        }
                    }

                    DashboardMapView(
                        markers = uiState.markers,
                        onMarkerClick = onDeviceClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            } else {
                val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                val isReducedMotion = com.sentinel.admin.ui.common.rememberLiveReducedMotion()
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = {
                        if (!uiState.isRefreshing) onRefresh()
                    },
                    state = pullState,
                    indicator = {
                        com.sentinel.admin.ui.common.SentinelRefreshIndicator(
                            state = pullState,
                            isRefreshing = uiState.isRefreshing,
                            operation = uiState.refreshOperation,
                            onConsumeOutcome = onConsumeRefreshOutcome,
                            reducedMotion = isReducedMotion,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        uiState.emergencyAlert?.let { alert ->
                            item(key = "header_emergency_alert") {
                                EmergencyAlertBanner(
                                    alert = alert,
                                    onSilence = onDismissEmergency,
                                    onViewDevice = {
                                        val targetKey = uiState.devices.find {
                                            (alert.callsign.isNotBlank() && it.resolvedCallsign.equals(alert.callsign, ignoreCase = true)) ||
                                            (alert.deviceId != "HOST-001" && it.deviceId == alert.deviceId)
                                        }?.uniqueKey ?: alert.deviceId
                                        onDeviceClick(targetKey)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }

                        item(key = "header_fleet_overview") {
                            FleetOverviewCard(
                                total = uiState.devices.size,
                                online = onlineCount,
                                isLoading = uiState.isLoading,
                                isCompact = isCompact,
                                modifier = Modifier.padding(vertical = if (isCompact) 2.dp else 4.dp)
                            )
                        }

                        item(key = "header_search_bar") {
                            SearchBar(
                                query = uiState.searchQuery,
                                onQueryChanged = onSearchQueryChanged,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = if (isCompact) 2.dp else 4.dp)
                            )
                        }

                        item(key = "header_filter_pills") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FleetFilter.entries.forEach { filter ->
                                    val count = when (filter) {
                                        FleetFilter.ALL -> uiState.devices.size
                                        FleetFilter.ONLINE -> uiState.devices.count { it.heartbeatStatus == "online" }
                                        FleetFilter.LOW_BATTERY -> uiState.devices.count {
                                            val b = it.latestLocation?.battery ?: -1
                                            b in 0..20
                                        }
                                        FleetFilter.EMERGENCY -> {
                                            val alertKey = uiState.emergencyAlert?.deviceId
                                            if (alertKey != null) {
                                                uiState.devices.count { it.deviceId == alertKey || it.uniqueKey == alertKey || it.connectionId == alertKey }
                                            } else 0
                                        }
                                    }
                                    val isSelected = uiState.fleetFilter == filter
                                    val tint by androidx.compose.animation.animateColorAsState(
                                        targetValue = if (isSelected) FigmaAccentBlue else FigmaTextMuted,
                                        animationSpec = androidx.compose.animation.core.tween(160),
                                        label = "filterTint"
                                    )
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
                                            .clickable { onFleetFilterChanged(filter) }
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                            .semantics {
                                                role = Role.Tab
                                                contentDescription = "Filter fleet by ${filter.label}, $count devices"
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = filter.label,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = tint
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isSelected) FigmaAccentBlue.copy(alpha = 0.2f) else Color(0x33FFFFFF))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "$count",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = tint
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.isLoading) {
                            item(key = "header_loading_bar") {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        when {
                            uiState.isLoading && uiState.devices.isEmpty() -> {
                                item(key = "state_loading") {
                                    LoadingState()
                                }
                            }
                            uiState.errorMessage != null -> {
                                item(key = "state_error") {
                                    ErrorState(
                                        message = uiState.errorMessage,
                                        onRetry = onRetry
                                    )
                                }
                            }
                            uiState.isEmpty -> {
                                item(key = "state_empty") {
                                    EmptyState(
                                        onRefresh = onRefresh,
                                        onDemoDeviceClick = { onDeviceClick("HOST-0001") }
                                    )
                                }
                            }
                            uiState.isSearchEmpty -> {
                                item(key = "state_search_empty") {
                                    SearchEmptyState(
                                        query = uiState.searchQuery,
                                        filterMessage = uiState.emptyFilterMessage
                                    )
                                }
                            }
                            else -> {
                                if (uiState.devices.isNotEmpty()) {
                                    item(key = "header_devices_title") {
                                        Text(
                                            text = "Devices",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = FigmaTextPrimary,
                                            modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                items(
                                    items = uiState.displayDevices,
                                    key = { it.uniqueKey }
                                ) { device ->
                                    DeviceCard(
                                        device = device,
                                        onClick = { onDeviceClick(device.uniqueKey) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIamDialog) {
        com.sentinel.admin.ui.iam.SovereignIamDialog(
            onDismiss = { showIamDialog = false }
        )
    }
}

// ============================================================
// Fleet Overview
// ============================================================

@Composable
private fun FleetOverviewCard(
    total: Int,
    online: Int,
    isLoading: Boolean = false,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val offlineCount = (total - online).coerceAtLeast(0)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)
    ) {
        // Left KPI Card: Online
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(if (isCompact) 14.dp else 20.dp),
            colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
            border = BorderStroke(1.dp, FigmaCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isCompact) 10.dp else 18.dp)
            ) {
                Text(
                    text = "Online",
                    fontSize = if (isCompact) 11.sp else 13.sp,
                    color = FigmaTextMuted
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$online / $total",
                    fontSize = if (isCompact) 18.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )
                if (!isCompact) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isLoading) "Syncing…" else "Live fleet status",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isLoading) FigmaAccentAmber else FigmaAccentGreen
                    )
                }
            }
        }

        // Right KPI Card: Offline (Work Order 3: renamed from Needs attention)
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(if (isCompact) 14.dp else 20.dp),
            colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
            border = BorderStroke(1.dp, FigmaCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isCompact) 10.dp else 18.dp)
            ) {
                Text(
                    text = "Offline",
                    fontSize = if (isCompact) 11.sp else 13.sp,
                    color = FigmaTextMuted
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$offlineCount",
                    fontSize = if (isCompact) 18.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )
                if (!isCompact) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (offlineCount > 0) "$offlineCount unreachable" else "All devices reachable",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (offlineCount > 0) FigmaAccentAmber else FigmaAccentGreen
                    )
                }
            }
        }
    }
}

// ============================================================
// Search Bar
// ============================================================

@Composable
private fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = { Text("Search units by name or ID…", color = Color(0xFF7D8997), fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFAAC7E8), modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Color(0xFF9AA7B6), modifier = Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF151B22),
            unfocusedContainerColor = Color(0xFF151B22),
            focusedBorderColor = Color(0xFFAAC7E8),
            unfocusedBorderColor = Color(0xFF293542),
            focusedTextColor = Color(0xFFF0F2F4),
            unfocusedTextColor = Color(0xFFF0F2F4)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    )
}

// ============================================================
// Device Card
// ============================================================

@Composable
private fun DeviceCard(
    device: Device,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnline = device.heartbeatStatus == "online"
    val loc = device.latestLocation
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.988f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "deviceCardPressScale"
    )
    val pressTilt by animateFloatAsState(
        targetValue = if (pressed) 0.3f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "deviceCardPressTilt"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                rotationY = pressTilt
                cameraDistance = 18 * density
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF151B22)
        ),
        border = BorderStroke(1.dp, if (isOnline) Color(0x3300E5FF) else Color(0xFF293542)),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 7.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            // Header row: Icon + name + model + online badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DeviceArtwork(
                        modifier = Modifier.padding(end = 12.dp),
                        width = 48.dp,
                        height = 66.dp,
                        model = "${device.model} ${device.deviceName}"
                    )
                    val displayName = device.deviceName.ifBlank { device.model.ifBlank { "Unit ${device.displayId}" } }
                    val displayModel = device.model.ifBlank { "Mobile Unit" }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF0F2F4),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$displayModel · ${device.displayId}",
                            fontSize = 11.sp,
                            color = Color(0xFF8FB2D8),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Status badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isOnline) Color(0x1F10B981) else Color(0x1F64748B))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) Color(0xFF10B981) else Color(0xFF7D8997))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isOnline) "ONLINE" else "OFFLINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnline) Color(0xFF10B981) else Color(0xFF9AA7B6),
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Telemetry Inset Micro-Tiles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B0F14))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Battery
                val batt = loc?.battery ?: 0
                val battColor = when {
                    batt > 50 -> Color(0xFF10B981)
                    batt > 20 -> Color(0xFFF59E0B)
                    else -> Color(0xFFF43F5E)
                }
                val isCharging = loc?.network?.contains("(Charging)") == true
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "BATTERY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = Color(0xFF7D8997)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isCharging) Icons.Default.BatteryChargingFull else if (batt > 50) Icons.Default.BatteryFull else Icons.Default.Battery4Bar,
                            contentDescription = null,
                            tint = battColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (loc != null) "$batt%" else "--",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = battColor
                        )
                    }
                }

                // Uplink / Network
                val cleanNetwork = loc?.network?.replace(" (Charging)", "") ?: "Unknown"
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "UPLINK",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = Color(0xFF7D8997)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (cleanNetwork.contains("WiFi", ignoreCase = true)) Icons.Default.Wifi else Icons.Default.NetworkCell,
                            contentDescription = null,
                            tint = Color(0xFF8FB2D8),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = cleanNetwork,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF0F2F4),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Location / GPS (Work Order Pass 9 R06)
                val hasValidFix = loc != null && loc.hasReportedFix && com.sentinel.admin.ui.map.isValidCoordinate(loc.latitude, loc.longitude)
                val gpsSummary = when {
                    !hasValidFix -> "No fix"
                    !loc.accuracy.isFinite() || loc.accuracy <= 0.0 -> "Accuracy unknown"
                    else -> "±${loc.accuracy.toInt()}m"
                }
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "LOCATION",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = Color(0xFF7D8997)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (hasValidFix) Color(0xFFAAC7E8) else Color(0xFF7D8997),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = gpsSummary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasValidFix) Color(0xFFAAC7E8) else Color(0xFF7D8997)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer: registration state + last heartbeat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "● ${device.registrationState.uppercase()}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = Color(0xFF7D8997)
                )
                Text(
                    text = "HEARTBEAT: ${formatTimestamp(device.lastHeartbeat)}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9AA7B6)
                )
            }
        }
    }
}

@Composable
private fun InfoChip(
    icon: @Composable () -> Unit,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        icon()
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================
// State screens
// ============================================================

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading devices…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(
    onRefresh: () -> Unit,
    onDemoDeviceClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.DevicesOther,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No devices found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connect a Host device to begin monitoring",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh")
                }
                OutlinedButton(onClick = onDemoDeviceClick) {
                    Icon(Icons.Default.DevicesOther, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Inspect Device Details")
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyState(
    query: String,
    filterMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val message = filterMessage ?: if (query.isNotBlank()) "No results for \"$query\"" else "No matching devices found"
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

// ============================================================
// Utility
// ============================================================

/**
 * Formats an ISO-8601 timestamp for display.
 * Shows time portion only for readability in cards.
 */
private fun formatTimestamp(iso: String): String {
    return try {
        // Extract HH:mm from "2026-07-09T12:00:20Z"
        val timePart = iso.substringAfter("T").substringBefore("Z")
        timePart.substring(0, 5)
    } catch (_: Exception) {
        iso
    }
}

@Composable
fun EmergencyAlertBanner(
    alert: com.sentinel.admin.domain.model.EmergencyAlert,
    onSilence: () -> Unit,
    onViewDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B0D11)),
        border = BorderStroke(1.5.dp, Color(0xFFFF4D4D))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Emergency Alert",
                    tint = Color(0xFFFF4D4D),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val alertTitle = when {
                        alert.triggerReason.contains("GEOFENCE", ignoreCase = true) ||
                        alert.triggerReason.contains("PERIMETER", ignoreCase = true) ->
                            "🚨 PERIMETER BREACH: ${alert.callsign}"
                        alert.triggerReason.contains("BATTERY", ignoreCase = true) ->
                            "🚨 CRITICAL BATTERY: ${alert.callsign}"
                        else ->
                            "🚨 FALL DETECTED: ${alert.callsign}"
                    }
                    Text(
                        text = alertTitle,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    val detailSubtitle = if (alert.impactGForce > 0.0) {
                        "${alert.model} • Impact: ${String.format(java.util.Locale.US, "%.1f", alert.impactGForce)}g • Battery: ${alert.battery ?: 0}%"
                    } else {
                        "${alert.model} • ${alert.triggerReason} • Battery: ${alert.battery ?: 0}%"
                    }
                    Text(
                        text = detailSubtitle,
                        color = Color(0xFFFFB3B3),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onSilence,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFFB3B3)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFFF4D4D).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Silence Alarm", fontSize = 12.sp)
                }

                Button(
                    onClick = onViewDevice,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF3333)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("View Unit", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

