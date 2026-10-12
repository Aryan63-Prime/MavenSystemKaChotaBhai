package com.sentinel.admin.ui.detail

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.provider.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.sentinel.admin.ui.common.rememberLiveReducedMotion
import com.sentinel.admin.ui.common.SentinelRefreshIndicator
import com.sentinel.admin.ui.common.RefreshStatus
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.sentinel.admin.domain.model.AudioStatistics
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceContactBook
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.domain.model.PlaybackState
import com.sentinel.admin.ui.DeviceArtwork
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaScreenBackground
import com.sentinel.admin.ui.theme.FigmaTabSelected
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary

enum class DetailTab(val label: String) {
    TELEMETRY("Telemetry"),
    ROUTE("Route"),
    MEDIA("Media")
}

/**
 * Device Detail screen — displays all available information for a single device.
 *
 * Stateless composable — all state from [DeviceDetailUiState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    uiState: DeviceDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onListenClick: () -> Unit = {},
    onStopClick: () -> Unit = {},
    onRecordToggle: () -> Unit = {},
    onRecordingsClick: () -> Unit = {},
    onFilesClick: (String) -> Unit = {},
    onSystemInfoClick: () -> Unit = {},
    onTriggerBeaconClick: () -> Unit = {},
    onCapturePhotoClick: (useFront: Boolean) -> Unit = {},
    onFetchLogsClick: () -> Unit = {},
    onFetchAppLogsClick: (fullDevice: Boolean, filter: String) -> Unit = { _, _ -> },
    onClearAppLogsClick: () -> Unit = {},
    onFetchNotifLogsClick: () -> Unit = {},
    onExecuteShellClick: (String) -> Unit = {},
    onCaptureScreenshotClick: () -> Unit = {},
    onPttStart: () -> Unit = {},
    onPttStop: () -> Unit = {},
    onTogglePttArm: (Boolean) -> Unit = {},
    onSyncContactClick: () -> Unit = {},
    onOpenAddressBookClick: () -> Unit = {},
    onFetchCallLogsClick: () -> Unit = {},
    onFetchInstalledAppsClick: () -> Unit = {},
    onLockDeviceClick: () -> Unit = {},
    onArmGeofenceClick: () -> Unit = {},
    onToggleAntiTamper: (Boolean) -> Unit = {},
    onEnforcePermissions: () -> Unit = {},
    onRefreshMdmStatus: () -> Unit = {},
    onDismissMdmMessage: () -> Unit = {},
    onDismissDialogs: () -> Unit = {},
    onWakeDeviceClick: () -> Unit = {},
    onConsumeRefreshOutcome: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showLockConfirmation by remember { mutableStateOf(false) }
    var showBeaconConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFF0B0F14),
        topBar = {
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.device?.let { device ->
                                device.deviceName.ifBlank { device.model.ifBlank { "Device ${device.displayId}" } }
                            } ?: "Device Details",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF0F2F4),
                            fontSize = if (fontScale >= 1.3f) 16.sp else 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (fontScale < 1.3f) {
                            uiState.device?.let { dev ->
                                Text(
                                    text = "${dev.model} · ${dev.displayId}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF8FB2D8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFF0F2F4)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onWakeDeviceClick, enabled = !uiState.isWakingDevice) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Wake Host via FCM",
                            tint = if (uiState.isWakingDevice) Color(0xFFFBBF24) else Color(0xFF34D399)
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color(0xFFAAC7E8)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0B0F14),
                    titleContentColor = Color(0xFFF0F2F4)
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading && uiState.device == null -> {
                    LoadingState(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    )
                }
                uiState.errorMessage != null && uiState.device == null -> {
                    ErrorState(
                        message = uiState.errorMessage,
                        onRetry = onRetry,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    )
                }
                uiState.device != null -> {
                    val pullState = rememberPullToRefreshState()
                    val isReducedMotion = rememberLiveReducedMotion()
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = {
                            if (!uiState.isRefreshing) onRefresh()
                        },
                        state = pullState,
                        indicator = {
                            SentinelRefreshIndicator(
                                state = pullState,
                                isRefreshing = uiState.isRefreshing,
                                operation = uiState.refreshOperation,
                                onConsumeOutcome = onConsumeRefreshOutcome,
                                reducedMotion = isReducedMotion,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        DeviceContent(
                            device = uiState.device,
                            isOnline = uiState.isOnline,
                            playbackState = uiState.playbackState,
                            audioStats = uiState.audioStats,
                            isRecording = uiState.isRecording,
                            recordingDurationMs = uiState.recordingDurationMs,
                            contactBook = uiState.contactBook,
                            isSyncingContacts = uiState.isSyncingContacts,
                            onSyncContactClick = onSyncContactClick,
                            onOpenAddressBookClick = onOpenAddressBookClick,
                            onFetchCallLogsClick = onFetchCallLogsClick,
                            onFetchInstalledAppsClick = onFetchInstalledAppsClick,
                            onListenClick = onListenClick,
                            onStopClick = onStopClick,
                            onRecordToggle = onRecordToggle,
                            onRecordingsClick = onRecordingsClick,
                            onFilesClick = { onFilesClick(uiState.device.deviceId) },
                            onSystemInfoClick = onSystemInfoClick,
                            onTriggerBeaconClick = { showBeaconConfirmation = true },
                            onCapturePhotoClick = onCapturePhotoClick,
                            onFetchLogsClick = onFetchLogsClick,
                            hostAppLogs = uiState.hostAppLogs,
                            isFetchingAppLogs = uiState.isFetchingAppLogs,
                            onFetchAppLogsClick = onFetchAppLogsClick,
                            onClearAppLogsClick = onClearAppLogsClick,
                            onFetchNotifLogsClick = onFetchNotifLogsClick,
                            onExecuteShellClick = { onExecuteShellClick("uptime") },
                            onCaptureScreenshotClick = onCaptureScreenshotClick,
                            isPttArmed = uiState.isPttArmed,
                            isPttTransmitting = uiState.isPttTransmitting,
                            pttAudioLevel = uiState.pttAudioLevel,
                            onTogglePttArm = onTogglePttArm,
                            onPttStart = onPttStart,
                            onPttStop = onPttStop,
                            isDeviceAdminActive = uiState.isDeviceAdminActive,
                            isDeviceOwner = uiState.isDeviceOwner,
                            isAntiTamperEnabled = uiState.isAntiTamperEnabled,
                            isLockingDevice = uiState.isLockingDevice,
                            mdmActionMessage = uiState.mdmActionMessage,
                            onLockDeviceClick = { if (!uiState.isLockingDevice) showLockConfirmation = true },
                            onArmGeofenceClick = onArmGeofenceClick,
                            onToggleAntiTamper = onToggleAntiTamper,
                            onEnforcePermissions = onEnforcePermissions,
                            onRefreshMdmStatus = onRefreshMdmStatus,
                            onDismissMdmMessage = onDismissMdmMessage,
                            isWakingDevice = uiState.isWakingDevice,
                            wakeStatusMessage = uiState.wakeStatusMessage,
                            onWakeDeviceClick = onWakeDeviceClick,
                            capturedPhotoBase64 = uiState.capturedPhotoBase64,
                            capturedPhotoFacing = uiState.capturedPhotoFacing,
                            onPhotoClick = { photoData, facing ->
                                // Trigger dialog
                            },
                            errorMessage = uiState.errorMessage,
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Render Air Command Dialogs
            if (uiState.showDiagnosticsDialog) {
                SystemDiagnosticsDialog(data = uiState.diagnosticsData, onDismiss = onDismissDialogs)
            }
            if (uiState.showPhotoDialog) {
                PhotoViewerDialog(
                    imageBase64 = uiState.capturedPhotoBase64,
                    cameraFacing = uiState.capturedPhotoFacing,
                    onDismiss = onDismissDialogs,
                    onSwitchCamera = { useFront -> onCapturePhotoClick(useFront) }
                )
            }
            if (uiState.showShellDialog) {
                ShellOutputDialog(lastOutput = uiState.shellOutput, onExecute = onExecuteShellClick, onDismiss = onDismissDialogs)
            }
            if (uiState.showLogsDialog) {
                LogsViewerDialog(logs = uiState.logsList, onDismiss = onDismissDialogs)
            }
            if (uiState.showNotifLogsDialog) {
                NotificationLogDialog(logsJsonRaw = uiState.notifLogsJsonRaw, onDismiss = onDismissDialogs)
            }
            if (uiState.showAddressBookDialog && uiState.contactBook != null) {
                AddressBookDialog(contactBook = uiState.contactBook, onDismiss = onDismissDialogs)
            }
            if (uiState.showScreenshotDialog) {
                ScreenshotViewerDialog(payload = uiState.screenshotPayload, onDismiss = onDismissDialogs)
            }
            if (uiState.showPreviewDialog) {
                FilePreviewDialog(payload = uiState.previewPayload, onDismiss = onDismissDialogs)
            }
            if (uiState.showCallLogsDialog) {
                CallLogViewerDialog(callLogs = uiState.callLogsList, onDismiss = onDismissDialogs)
            }
            if (uiState.showInstalledAppsDialog) {
                InstalledAppsDialog(apps = uiState.installedAppsList, onDismiss = onDismissDialogs)
            }
            if (showLockConfirmation) {
                AlertDialog(
                    onDismissRequest = { showLockConfirmation = false },
                    title = { Text("Lock this device?") },
                    text = { Text("The device screen will lock immediately. Its user may need their screen lock to continue.") },
                    confirmButton = {
                        TextButton(
                            enabled = !uiState.isLockingDevice,
                            onClick = {
                                showLockConfirmation = false
                                onLockDeviceClick()
                            }
                        ) { Text(if (uiState.isLockingDevice) "Locking…" else "Lock screen") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLockConfirmation = false }) { Text("Cancel") }
                    },
                    containerColor = Color(0xFF171F28),
                    titleContentColor = Color(0xFFF0F2F4),
                    textContentColor = Color(0xFFBFC9D5)
                )
            }
            if (showBeaconConfirmation) {
                AlertDialog(
                    onDismissRequest = { showBeaconConfirmation = false },
                    title = { Text("Activate the siren beacon?") },
                    text = { Text("This will play a loud alert through the device speaker.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showBeaconConfirmation = false
                                onTriggerBeaconClick()
                            }
                        ) { Text("Activate beacon") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBeaconConfirmation = false }) { Text("Cancel") }
                    },
                    containerColor = Color(0xFF171F28),
                    titleContentColor = Color(0xFFF0F2F4),
                    textContentColor = Color(0xFFBFC9D5)
                )
            }
        }
    }
}

// ============================================================
// Device Content
// ============================================================

@Composable
private fun DeviceContent(
    device: Device,
    isOnline: Boolean,
    playbackState: PlaybackState,
    audioStats: AudioStatistics,
    isRecording: Boolean,
    recordingDurationMs: Long,
    contactBook: DeviceContactBook? = null,
    isSyncingContacts: Boolean = false,
    onSyncContactClick: () -> Unit = {},
    onOpenAddressBookClick: () -> Unit = {},
    onFetchCallLogsClick: () -> Unit = {},
    onFetchInstalledAppsClick: () -> Unit = {},
    onListenClick: () -> Unit,
    onStopClick: () -> Unit,
    onRecordToggle: () -> Unit,
    onRecordingsClick: () -> Unit,
    onFilesClick: () -> Unit,
    onSystemInfoClick: () -> Unit = {},
    onTriggerBeaconClick: () -> Unit = {},
    onCapturePhotoClick: (useFront: Boolean) -> Unit = {},
    onFetchLogsClick: () -> Unit = {},
    hostAppLogs: List<String> = emptyList(),
    isFetchingAppLogs: Boolean = false,
    onFetchAppLogsClick: (fullDevice: Boolean, filter: String) -> Unit = { _, _ -> },
    onClearAppLogsClick: () -> Unit = {},
    onFetchNotifLogsClick: () -> Unit = {},
    onExecuteShellClick: () -> Unit = {},
    onCaptureScreenshotClick: () -> Unit = {},
    isPttArmed: Boolean = false,
    isPttTransmitting: Boolean = false,
    pttAudioLevel: Float = 0f,
    onTogglePttArm: (Boolean) -> Unit = {},
    onPttStart: () -> Unit = {},
    onPttStop: () -> Unit = {},
    isDeviceAdminActive: Boolean = false,
    isDeviceOwner: Boolean = false,
    isAntiTamperEnabled: Boolean = false,
    isLockingDevice: Boolean = false,
    mdmActionMessage: String? = null,
    onLockDeviceClick: () -> Unit = {},
    onArmGeofenceClick: () -> Unit = {},
    onToggleAntiTamper: (Boolean) -> Unit = {},
    onEnforcePermissions: () -> Unit = {},
    onRefreshMdmStatus: () -> Unit = {},
    onDismissMdmMessage: () -> Unit = {},
    isWakingDevice: Boolean = false,
    wakeStatusMessage: String? = null,
    onWakeDeviceClick: () -> Unit = {},
    capturedPhotoBase64: String = "",
    capturedPhotoFacing: String = "REAR",
    onPhotoClick: (String, String) -> Unit = { _, _ -> },
    errorMessage: String? = null,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable(device.uniqueKey) { mutableStateOf(DetailTab.TELEMETRY) }
    var batteryExpanded by rememberSaveable(device.uniqueKey) { mutableStateOf(true) }
    var showSensorHistory by remember { mutableStateOf(false) }
    val tabStates = rememberSaveableStateHolder()

    val outerScrollState = rememberScrollState()
    val telemetryScrollState = rememberScrollState()
    val routeScrollState = rememberScrollState()
    val mediaScrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val configuration = LocalConfiguration.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fontScale = density.fontScale
        val hasBanners = errorMessage != null || wakeStatusMessage != null
        val isShortWindow = isLandscape || maxHeight < 580.dp || (maxHeight < 740.dp && (fontScale >= 1.25f || hasBanners))
        val isCompact = isLandscape || maxHeight < 550.dp || isShortWindow

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isShortWindow) Modifier.verticalScroll(outerScrollState) else Modifier)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (isCompact) 8.dp else 16.dp,
                    top = if (isCompact) 4.dp else 8.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 14.dp)
        ) {
        // Status header
        StatusHeader(
            device = device,
            isOnline = isOnline,
            isWaking = isWakingDevice,
            onWakeClick = onWakeDeviceClick,
            isCompact = isCompact
        )

        // Durable inline RefreshErrorBanner
        if (errorMessage != null) {
            RefreshErrorBanner(
                message = errorMessage,
                onRetry = onRefresh
            )
        }

        // FCM Wake status notification banner
        if (wakeStatusMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A8A).copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, Color(0xFF3B82F6))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = wakeStatusMessage,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFBFDBFE),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        val context = LocalContext.current
        val isReducedMotion = rememberLiveReducedMotion()

        val screenScope = rememberCoroutineScope()
        val geofenceDraftStore = remember(context) { com.sentinel.admin.ui.map.GeofenceDraftStore.getDefault(context) }
        val geofenceStateHolder = rememberSaveable(
            device.uniqueKey,
            saver = com.sentinel.admin.ui.map.GeofenceEditorStateHolder.saver(
                draftStore = geofenceDraftStore,
                ownerScope = screenScope,
                fallbackLat = device.latestLocation?.latitude,
                fallbackLon = device.latestLocation?.longitude
            )
        ) {
            com.sentinel.admin.ui.map.GeofenceEditorStateHolder(
                initialDeviceId = device.uniqueKey,
                draftStore = geofenceDraftStore,
                ownerScope = screenScope,
                fallbackLat = device.latestLocation?.latitude,
                fallbackLon = device.latestLocation?.longitude
            )
        }

        // --- Redesigned Navigation Rail: SentinelTabs (Work Order 8.A) ---
        SentinelTabs(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            reducedMotion = isReducedMotion
        )

        // --- Single-body reveal (Work Order 8.B: mounts ONE active tab body) ---
        val reveal = remember { Animatable(1f) }
        LaunchedEffect(selectedTab, isReducedMotion) {
            if (isReducedMotion) {
                reveal.snapTo(1f)
            } else {
                reveal.snapTo(0f)
                reveal.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
            }
        }
        Box(
            modifier = Modifier
                .then(if (isShortWindow) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth())
                .graphicsLayer {
                    alpha = reveal.value
                    translationY = (1f - reveal.value) * 4.dp.toPx()
                }
        ) {
            val tabModifier = if (isShortWindow) {
                Modifier.fillMaxWidth()
            } else {
                when (selectedTab) {
                    DetailTab.TELEMETRY -> Modifier.fillMaxSize().verticalScroll(telemetryScrollState)
                    DetailTab.ROUTE -> Modifier.fillMaxSize().verticalScroll(routeScrollState)
                    DetailTab.MEDIA -> Modifier.fillMaxSize().verticalScroll(mediaScrollState)
                }
            }

            tabStates.SaveableStateProvider(key = "${device.uniqueKey}:${selectedTab.name}") {
                when (selectedTab) {
                    DetailTab.TELEMETRY -> {
                        Column(
                            modifier = tabModifier,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // 1. Live Audio & PTT Card (Figma Concept 02 & 07, Work Orders 1 & 2)
                            LiveAudioCard(
                                playbackState = playbackState,
                                audioStats = audioStats,
                                onListenClick = onListenClick,
                                onStopClick = onStopClick,
                                isOnline = isOnline,
                                isPttArmed = isPttArmed,
                                onPttArmedChange = onTogglePttArm,
                                isPttTransmitting = isPttTransmitting,
                                pttAudioLevel = pttAudioLevel,
                                onPttStart = onPttStart,
                                onPttStop = onPttStop,
                                isRecording = isRecording,
                                recordingDurationMs = recordingDurationMs,
                                onRecordToggle = onRecordToggle,
                                onRecordingsClick = onRecordingsClick
                            )

                            // 2. Battery & Power Collapsible Accordion (Work Orders 3 & 6)
                            val batteryLevel = device.latestLocation?.battery
                            val isCharging = device.latestLocation?.network?.contains("(Charging)") == true
                            BatteryAccordionCard(
                                batteryLevel = batteryLevel,
                                isCharging = isCharging,
                                isOnline = isOnline,
                                isExpanded = batteryExpanded,
                                onExpandedChange = { batteryExpanded = it }
                            )

                            // 3. Network & Motion Summary (Work Orders 3 & 4)
                            val networkStr = device.latestLocation?.network?.replace(" (Charging)", "")?.takeIf { it.isNotBlank() }
                            NetworkMotionCard(
                                networkType = networkStr,
                                signalDbm = null,
                                accelerationG = null,
                                isOnline = isOnline,
                                onInspectHistory = { showSensorHistory = true }
                            )

                            // 4. Remote Air Commands Engine
                            AirCommandCard(
                                isOnline = isOnline,
                                onSystemInfoClick = onSystemInfoClick,
                                onTriggerBeaconClick = onTriggerBeaconClick,
                                onCapturePhotoClick = onCapturePhotoClick,
                                onFetchLogsClick = onFetchLogsClick,
                                onFetchNotifLogsClick = onFetchNotifLogsClick,
                                onExecuteShellClick = onExecuteShellClick,
                                onCaptureScreenshotClick = onCaptureScreenshotClick,
                                onLockDeviceClick = onLockDeviceClick,
                                onArmGeofenceClick = onArmGeofenceClick,
                                onFetchCallLogsClick = onFetchCallLogsClick,
                                onFetchInstalledAppsClick = onFetchInstalledAppsClick
                            )

                            // 5. Realtime Host Application & System Logs
                            HostAppLogsCard(
                                logs = hostAppLogs,
                                isOnline = isOnline,
                                isFetching = isFetchingAppLogs,
                                onFetchLogs = onFetchAppLogsClick,
                                onClearLogs = onClearAppLogsClick
                            )

                            // 6. Enterprise MDM & Policy Card
                            MdmPolicyCard(
                                isOnline = isOnline,
                                isDeviceAdminActive = isDeviceAdminActive,
                                isDeviceOwner = isDeviceOwner,
                                isAntiTamperEnabled = isAntiTamperEnabled,
                                isLockingDevice = isLockingDevice,
                                actionMessage = mdmActionMessage,
                                onLockDevice = onLockDeviceClick,
                                onToggleAntiTamper = onToggleAntiTamper,
                                onEnforcePermissions = onEnforcePermissions,
                                onRefreshStatus = onRefreshMdmStatus,
                                onDismissMessage = onDismissMdmMessage
                            )

                            // 7. File system controls
                            FileControlCard(
                                onFilesClick = onFilesClick
                            )

                            // 8. Contact & Address Book Card
                            ContactDetailsCard(
                                contactBook = contactBook,
                                isSyncing = isSyncingContacts,
                                onSyncClick = onSyncContactClick,
                                onOpenAddressBookClick = onOpenAddressBookClick
                            )

                            // 9. Technical Device & Connection Card (closeable / revealable)
                            DeviceConnectionCard(device = device, isOnline = isOnline)
                        }
                    }
                    DetailTab.ROUTE -> {
                        Column(
                            modifier = tabModifier,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            RouteHistoryView(
                                deviceKey = device.uniqueKey,
                                deviceName = device.deviceName.ifBlank { device.model },
                                location = device.latestLocation,
                                isOnline = isOnline,
                                geofenceStateHolder = geofenceStateHolder
                            )
                        }
                    }
                    DetailTab.MEDIA -> {
                        Column(
                            modifier = tabModifier,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            MediaGalleryView(
                                capturedPhotoBase64 = capturedPhotoBase64,
                                capturedPhotoFacing = capturedPhotoFacing
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

    if (showSensorHistory) {
        SensorHistoryDialog(
            deviceName = device.deviceName.ifBlank { device.model },
            currentBattery = device.latestLocation?.battery,
            signalDbm = null,
            accelerationG = null,
            isOnline = isOnline,
            onDismiss = { showSensorHistory = false }
        )
    }
}

/**
 * Shared quiet navigation rail with sliding capsule underlay.
 *
 * Implements Work Order 8.A:
 * - 16dp rounded rail with 4dp inner padding
 * - 12dp rounded selected capsule sliding to measured slot in 220ms (FastOutSlowInEasing)
 * - 160ms tint transition for icons and labels
 * - 18dp outline icons with visible labels (NO checkmark prefix)
 * - Minimum 48dp touch targets
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SentinelTabs(
    selectedTab: DetailTab,
    onTabSelected: (DetailTab) -> Unit,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier
) {
    var itemBounds by remember { mutableStateOf<Map<DetailTab, Rect>>(emptyMap()) }
    var parentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    val duration = if (reducedMotion) 0 else 220
    val selectedBounds = itemBounds[selectedTab] ?: Rect.Zero

    // Avoid empty first-layout animations
    var hasSettledInitialLayout by remember { mutableStateOf(false) }
    LaunchedEffect(selectedBounds) {
        if (selectedBounds.width > 0f) {
            hasSettledInitialLayout = true
        }
    }

    val animDuration = if (!hasSettledInitialLayout) 0 else duration

    val capsuleX by animateFloatAsState(
        targetValue = selectedBounds.left,
        animationSpec = if (animDuration == 0) snap() else tween(animDuration, easing = FastOutSlowInEasing),
        label = "tabCapsuleX"
    )
    val capsuleWidth by animateFloatAsState(
        targetValue = selectedBounds.width,
        animationSpec = if (animDuration == 0) snap() else tween(animDuration, easing = FastOutSlowInEasing),
        label = "tabCapsuleWidth"
    )

    val bringIntoViewRequesters = remember {
        DetailTab.entries.associateWith { BringIntoViewRequester() }
    }

    // Layout-aware bring-into-view: Triggered strictly on tab selection changes, never on drag movements
    LaunchedEffect(selectedTab) {
        bringIntoViewRequesters[selectedTab]?.bringIntoView()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F151D))
            .border(1.dp, FigmaCardBorder, RoundedCornerShape(16.dp))
            .padding(4.dp)
            .clipToBounds()
            .onGloballyPositioned { coords ->
                parentCoordinates = coords
            }
    ) {
        // Sliding capsule underlay positioned via physical absolute coordinates from top-left
        if (selectedBounds.width > 0f) {
            val capsuleHeightDp = with(density) {
                if (selectedBounds.height > 0f) (selectedBounds.height.toDp() - 8.dp).coerceAtLeast(40.dp) else 40.dp
            }
            Box(
                Modifier
                    .absoluteOffset {
                        val parentH = parentCoordinates?.size?.height?.toFloat() ?: selectedBounds.height
                        val topY = if (parentH > 0f && selectedBounds.height > 0f) {
                            ((parentH - with(density) { capsuleHeightDp.toPx() }) / 2f).coerceAtLeast(0f)
                        } else 0f
                        IntOffset(capsuleX.roundToInt(), topY.roundToInt())
                    }
                    .width(with(density) { capsuleWidth.toDp() })
                    .height(capsuleHeightDp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(FigmaTabSelected)
            )
        }

        // Tab items row (min 48dp interactive slot, adaptively scrolls at narrow widths or large font scales)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DetailTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                val tint by animateColorAsState(
                    targetValue = if (isSelected) FigmaAccentBlue else FigmaTextMuted,
                    animationSpec = tween(if (reducedMotion) 0 else 160),
                    label = "tabTint"
                )

                Box(
                    modifier = Modifier
                        .bringIntoViewRequester(bringIntoViewRequesters.getValue(tab))
                        .defaultMinSize(minWidth = 104.dp)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onTabSelected(tab) }
                        )
                        .onGloballyPositioned { coords ->
                            parentCoordinates?.let { parent ->
                                if (coords.isAttached && parent.isAttached) {
                                    // Measure full tab rectangle without ancestor clipping!
                                    val relBounds = parent.localBoundingBoxOf(coords, clipBounds = false)
                                    itemBounds = itemBounds + (tab to relBounds)
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val iconVector = when (tab) {
                            DetailTab.TELEMETRY -> Icons.Default.Sensors
                            DetailTab.ROUTE -> Icons.AutoMirrored.Filled.AltRoute
                            DetailTab.MEDIA -> Icons.Default.PhotoLibrary
                        }
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tab.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1515)),
        border = BorderStroke(1.dp, Color(0xFF7F1D1D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Color(0xFFF87171),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                color = Color(0xFFFCA5A5),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = "Retry",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF60A5FA)
                )
            }
        }
    }
}

// ============================================================
// Status Header
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHeader(
    device: Device,
    isOnline: Boolean,
    isWaking: Boolean = false,
    onWakeClick: () -> Unit = {},
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val displayName = device.deviceName.ifBlank { device.model.ifBlank { "Device ${device.displayId}" } }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (isCompact) 16.dp else 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF121923)
        ),
        border = BorderStroke(1.dp, if (isOnline) Color(0xFF31558A) else Color(0xFF26354E)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCompact) 2.dp else 5.dp)
    ) {
        if (isCompact) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF10B981) else Color(0xFF7D8997))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF0F2F4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${if (isOnline) "ONLINE" else "OFFLINE"} · ${device.model} · ${device.displayId}",
                        fontSize = 11.sp,
                        color = Color(0xFF8FB2D8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onWakeClick,
                    enabled = !isWaking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isOnline) Color(0xFF1E293B) else Color(0xFF0F766E),
                        disabledContainerColor = Color(0xFF1A222C)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isWaking) Color(0xFFFBBF24) else Color(0xFF34D399)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isWaking) "Waking..." else if (isOnline) "FCM Ping" else "FCM Wake",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnline) Color(0xFFAAC7E8) else Color.White
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status avatar
                DeviceArtwork(
                    width = 66.dp,
                    height = 90.dp,
                    model = "${device.model} ${device.deviceName}"
                )
                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF0F2F4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            color = if (isOnline) Color(0xFF10B981) else Color(0xFF7D8997),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = device.model.ifBlank { "DEVICE" },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF8FB2D8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(9.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DeviceTag(text = device.displayId)
                        DeviceTag(text = if (device.authenticated) "SECURE LINK" else "UNVERIFIED")

                        Button(
                            onClick = onWakeClick,
                            enabled = !isWaking,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isOnline) Color(0xFF1E293B) else Color(0xFF0F766E),
                                disabledContainerColor = Color(0xFF1A222C)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (isWaking) Color(0xFFFBBF24) else Color(0xFF34D399)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isWaking) "Waking..." else if (isOnline) "FCM Ping" else "FCM Wake",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isOnline) Color(0xFFAAC7E8) else Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceTag(text: String) {
    Text(
        text = text,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = .3.sp,
        color = Color(0xFFB7C9DD),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF283847))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun DeviceTelemetryStrip(device: Device, isOnline: Boolean) {
    val location = device.latestLocation
    val network = location?.network?.replace(" (Charging)", "") ?: "No signal"
    val batteryColor = when {
        location == null -> Color(0xFF7184A4)
        location.battery > 50 -> Color(0xFF48E4B6)
        location.battery > 20 -> Color(0xFFFFCA72)
        else -> Color(0xFFFF7588)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        TelemetryTile(
            icon = if (location?.network?.contains("(Charging)") == true) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
            value = location?.let { "${it.battery}%" } ?: "--",
            label = "BATTERY",
            tint = batteryColor,
            modifier = Modifier.weight(1f)
        )
        TelemetryTile(
            icon = Icons.Default.NetworkCell,
            value = network,
            label = "UPLINK",
            tint = Color(0xFF8FB2D8),
            modifier = Modifier.weight(1f)
        )
        TelemetryTile(
            icon = Icons.Default.Wifi,
            value = if (isOnline) "Connected" else "Offline",
            label = "CONNECTION",
            tint = if (isOnline) Color(0xFF45E0B3) else Color(0xFF8494B0),
            modifier = Modifier.weight(1f)
        )
        TelemetryTile(
            icon = Icons.Default.LocationOn,
            value = location?.let { "±${it.accuracy.toInt()}m" } ?: "No fix",
            label = "LOCATION",
            tint = Color(0xFFAAC7E8),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TelemetryTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF171F28))
            .border(1.dp, Color(0xFF293542), RoundedCornerShape(14.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE8EDF2), maxLines = 1)
        Text(label, fontSize = 7.sp, letterSpacing = .3.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8B97A5), maxLines = 1)
    }
}

// ============================================================
// Technical Device & Connection Card (100% Closeable / Revealable)
// ============================================================

@Composable
private fun DeviceConnectionCard(device: Device, isOnline: Boolean) {
    var expanded by rememberSaveable(device.uniqueKey) { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171F28)),
        border = BorderStroke(1.dp, Color(0xFF293542)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DevicesOther,
                    contentDescription = null,
                    tint = Color(0xFFAAC7E8),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TECHNICAL DEVICE & CONNECTION SPECS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = .8.sp,
                        color = Color(0xFFAAC7E8)
                    )
                    Text(
                        text = if (expanded) "Tap to close specs" else "Tap to reveal connection telemetry",
                        fontSize = 10.sp,
                        color = Color(0xFF7A8B9E)
                    )
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Hide technical details" else "Show technical details",
                        tint = Color(0xFF9AA7B6),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    InfoRow("Model", device.model.ifBlank { "Unknown" })
                    InfoRow("App version", device.appVersion.ifBlank { "—" })
                    InfoRow("Connection", if (isOnline && device.authenticated) "Online · authenticated" else if (isOnline) "Online" else "Offline")
                    InfoRow("Last seen", formatTimestamp(device.lastHeartbeat))
                    InfoRow("Callsign", device.displayId)
                    InfoRow("Device Token", device.deviceId)
                    InfoRow("Registration", device.registrationState.replaceFirstChar { it.uppercase() })
                    InfoRow("Connected", formatTimestamp(device.connectedAt))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Connection ID", fontSize = 12.sp, color = Color(0xFF9AA7B6), fontWeight = FontWeight.Medium)
                        Text(
                            text = device.connectionId,
                            fontSize = 11.sp,
                            color = Color(0xFFF0F2F4),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(device.connectionId)) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy connection ID",
                                tint = Color(0xFFAAC7E8),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// Reusable Components
// ============================================================

@Composable
private fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF171F28)
        ),
        border = BorderStroke(1.dp, Color(0xFF293542)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFFAAC7E8)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = title.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color(0xFFAAC7E8)
                )
            }
            Spacer(modifier = Modifier.height(7.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF9AA7B6),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.85f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            color = Color(0xFFF0F2F4),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.15f)
        )
    }
}

// ============================================================
// State screens
// ============================================================

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading device…",
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
        modifier = modifier,
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
 * Formats an ISO-8601 timestamp for friendly display.
 * "2026-07-09T12:00:20Z" → "Jul 9, 2026 12:00"
 */
private fun formatTimestamp(iso: String): String {
    return try {
        val date = iso.substringBefore("T")
        val time = iso.substringAfter("T").substringBefore("Z").substring(0, 5)
        val parts = date.split("-")
        val months = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        val month = months[parts[1].toInt() - 1]
        val day = parts[2].toInt()
        val year = parts[0]
        "$month $day, $year $time"
    } catch (_: Exception) {
        iso
    }
}
