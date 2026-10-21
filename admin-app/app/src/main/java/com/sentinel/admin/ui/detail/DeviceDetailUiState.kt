package com.sentinel.admin.ui.detail

import com.sentinel.admin.domain.model.AudioStatistics
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceContactBook
import com.sentinel.admin.domain.model.PlaybackState

/**
 * Immutable UI state for the Device Detail screen.
 *
 * Produced by [DeviceDetailViewModel], consumed by [DeviceDetailScreen].
 */
data class DeviceDetailUiState(
    /** The loaded device, null until first successful load. */
    val device: Device? = null,
    /** Contact book extracted directly from the monitored device. */
    val contactBook: DeviceContactBook? = null,
    /** Whether contact synchronization is currently in progress. */
    val isSyncingContacts: Boolean = false,
    /** Whether the device is loading for the first time. */
    val isLoading: Boolean = false,
    /** Whether a pull-to-refresh is in progress. */
    val isRefreshing: Boolean = false,
    /** Authoritative refresh operation lifecycle state. */
    val refreshOperation: com.sentinel.admin.ui.common.RefreshOperation = com.sentinel.admin.ui.common.RefreshOperation.Idle,
    /** Error message, null if no error. */
    val errorMessage: String? = null,
    /** Audio playback state — observed from AudioMonitor, not owned by ViewModel. */
    val playbackState: PlaybackState = PlaybackState.Idle,
    /** Audio statistics — observed from AudioMonitor. */
    val audioStats: AudioStatistics = AudioStatistics(),
    /** Audio recording active status. */
    val isRecording: Boolean = false,
    /** Active recording duration in milliseconds. */
    val recordingDurationMs: Long = 0L,
    /** PTT Intercom safety switch: true = Armed (ready to transmit), false = Safe/Locked. */
    val isPttArmed: Boolean = false,
    /** Whether Push-To-Talk is actively transmitting mic audio to the host device. */
    val isPttTransmitting: Boolean = false,
    /** Live normalized peak audio amplitude [0f..1f] for PTT waveform visualizer. */
    val pttAudioLevel: Float = 0f,
    /** Air Command Dialog States */
    val showDiagnosticsDialog: Boolean = false,
    val diagnosticsData: Map<String, Any> = emptyMap(),
    val showPhotoDialog: Boolean = false,
    val capturedPhotoBase64: String = "",
    val capturedPhotoFacing: String = "REAR",
    val showShellDialog: Boolean = false,
    val shellOutput: String = "",
    val showLogsDialog: Boolean = false,
    val logsList: List<String> = emptyList(),
    val showNotifLogsDialog: Boolean = false,
    val notifLogsJsonRaw: String = "",
    val showAddressBookDialog: Boolean = false,
    val showScreenshotDialog: Boolean = false,
    val screenshotPayload: Map<String, Any?> = emptyMap(),
    val showPreviewDialog: Boolean = false,
    val previewPayload: Map<String, Any?> = emptyMap(),
    val isDeviceAdminActive: Boolean = false,
    val isDeviceOwner: Boolean = false,
    val isAntiTamperEnabled: Boolean = false,
    val isLockingDevice: Boolean = false,
    val mdmActionMessage: String? = null,
    val commandStatusMessage: String? = null,
    val hostAppLogs: List<String> = emptyList(),
    val isFetchingAppLogs: Boolean = false,
    val isWakingDevice: Boolean = false,
    val wakeStatusMessage: String? = null,
    val showCallLogsDialog: Boolean = false,
    val callLogsList: List<Map<String, Any>> = emptyList(),
    val showInstalledAppsDialog: Boolean = false,
    val installedAppsList: List<Map<String, Any>> = emptyList(),
    val signalDbm: Int? = null,
    val accelerationG: Float? = null,
    val routePoints: List<com.sentinel.admin.domain.model.DeviceLocation> = emptyList(),
    val nearestUnitSummary: String? = null,
    val estimatedAltitudeMeters: Double? = null,
    val estimatedFloor: Int? = null,
    val compassAzimuthDeg: Int? = null,
    val motionActivity: String? = null,
    val gnssSpoofSuspected: Boolean = false,
    val rogueCellSuspected: Boolean = false,
    val servingCellSummary: String? = null,
    val batteryHistory: List<Int> = emptyList(),
    val sessionMediaItems: List<MediaItem> = emptyList(),
    val incomingAudioLevel: Float = 0f,
    val incomingAudioDbFs: Int = -60,
    val incomingAudioFreqHz: Int = 0,
    val devicePoseAndCameraHint: String? = null,
    val ambientEnvironment: String? = null,
    val magneticFieldUt: Int? = null,
    val emfAnomalyDetected: Boolean = false,
    val seismicVibrationSummary: String? = null,
    val gnssConstellationSummary: String? = null,
    val batteryEnduranceSummary: String? = null,
    val cpuFrequencySummary: String? = null,
    val vpnActive: Boolean = false,
    val proxyActive: Boolean = false,
    val totalTrafficSummary: String? = null,
    val activeTcpSockets: Int? = null,
    val defconLevel: Int = 5,
    val defconSummary: String? = null,
    val mlModelStatus: String? = null,
    val usbHardwareSummary: String? = null,
    val usbIntrusionSuspected: Boolean = false,
    val simSecuritySummary: String? = null,
    val simTamperOrRoamingAlert: Boolean = false,
    val verticalMotionState: String? = null,
    val predictedInterceptSummary: String? = null,
    val incomingAcousticClass: String = "QUIET ROOM"
) {
    /** True if device loaded successfully. */
    val hasDevice: Boolean get() = device != null && errorMessage == null

    /** True if device is online. */
    val isOnline: Boolean get() = device?.heartbeatStatus == "online"
}
