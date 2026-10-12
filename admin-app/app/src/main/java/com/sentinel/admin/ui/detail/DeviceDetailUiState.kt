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
    val installedAppsList: List<Map<String, Any>> = emptyList()
) {
    /** True if device loaded successfully. */
    val hasDevice: Boolean get() = device != null && errorMessage == null

    /** True if device is online. */
    val isOnline: Boolean get() = device?.heartbeatStatus == "online"
}
