package com.sentinel.admin.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.ui.dashboard.DashboardScreen
import com.sentinel.admin.ui.dashboard.DashboardViewModel
import com.sentinel.admin.ui.detail.DeviceDetailScreen
import com.sentinel.admin.ui.detail.DeviceDetailViewModel
import com.sentinel.admin.ui.files.FileBrowserScreen
import com.sentinel.admin.ui.login.LoginScreen
import com.sentinel.admin.ui.login.LoginViewModel

/**
 * Navigation routes for the Admin application.
 */
object AdminRoutes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val DEVICE_DETAIL = "device/{deviceId}"
    const val FILE_BROWSER = "device/{deviceId}/files"
    const val RECORDINGS = "recordings"

    fun deviceDetail(deviceId: String) = "device/$deviceId"
    fun fileBrowser(deviceId: String) = "device/$deviceId/files"
}

/**
 * Admin navigation graph.
 *
 * Login → Dashboard → Device Detail (placeholder).
 */
@Composable
fun AdminNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AdminRoutes.LOGIN
    ) {
        composable(AdminRoutes.LOGIN) {
            val viewModel: LoginViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // Navigate to Dashboard when Ready
            LaunchedEffect(uiState.connectionState) {
                if (uiState.connectionState is ConnectionState.Ready) {
                    navController.navigate(AdminRoutes.DASHBOARD) {
                        popUpTo(AdminRoutes.LOGIN) { inclusive = true }
                    }
                }
            }

            LoginScreen(
                uiState = uiState,
                onServerUrlChanged = viewModel::onServerUrlChanged,
                onTokenChanged = viewModel::onTokenChanged,
                onRememberMeChanged = viewModel::onRememberMeChanged,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect
            )
        }

        composable(AdminRoutes.DASHBOARD) {
            val viewModel: DashboardViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            DashboardScreen(
                uiState = uiState,
                onRefresh = viewModel::refresh,
                onSearchQueryChanged = viewModel::onSearchQueryChanged,
                onSortOrderChanged = viewModel::onSortOrderChanged,
                onFleetFilterChanged = viewModel::onFleetFilterChanged,
                onViewModeChanged = viewModel::onViewModeChanged,
                onDeviceClick = { deviceId ->
                    navController.navigate(AdminRoutes.deviceDetail(deviceId))
                },
                onRecordingsClick = {
                    navController.navigate(AdminRoutes.RECORDINGS)
                },
                onRetry = viewModel::retry,
                onDismissEmergency = viewModel::dismissEmergencyAlert,
                onConsumeRefreshOutcome = viewModel::consumeRefreshOutcome
            )
        }

        composable(AdminRoutes.DEVICE_DETAIL) {
            val viewModel: DeviceDetailViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = androidx.compose.ui.platform.LocalContext.current

            DeviceDetailScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                onListenClick = viewModel::onListenClick,
                onStopClick = viewModel::onStopClick,
                onRecordToggle = { viewModel.toggleRecording(context) },
                onRecordingsClick = { navController.navigate(AdminRoutes.RECORDINGS) },
                onFilesClick = { deviceId -> 
                    navController.navigate(AdminRoutes.fileBrowser(deviceId))
                },
                onSystemInfoClick = viewModel::sendSystemInfoCommand,
                onTriggerBeaconClick = viewModel::sendTriggerBeaconCommand,
                onCapturePhotoClick = { useFront -> viewModel.sendCapturePhotoCommand(useFront) },
                onFetchLogsClick = viewModel::sendFetchLogsCommand,
                onFetchAppLogsClick = { fullDevice, filter -> viewModel.sendFetchAppLogsCommand(fullDevice, filter) },
                onClearAppLogsClick = viewModel::clearAppLogs,
                onFetchNotifLogsClick = viewModel::sendFetchNotificationLogsCommand,
                onExecuteShellClick = viewModel::sendExecuteShellCommand,
                onCaptureScreenshotClick = viewModel::sendCaptureScreenshotCommand,
                onPttStart = viewModel::startPtt,
                onPttStop = viewModel::stopPtt,
                onTogglePttArm = viewModel::setPttArmed,
                onSyncContactClick = viewModel::sendSyncContactsCommand,
                onOpenAddressBookClick = viewModel::openAddressBookDialog,
                onFetchCallLogsClick = viewModel::openCallLogsDialog,
                onFetchInstalledAppsClick = viewModel::openInstalledAppsDialog,
                onLockDeviceClick = viewModel::lockDevice,
                onArmGeofenceClick = { viewModel.armPerimeterGeofence(100f) },
                onToggleAntiTamper = viewModel::setAntiTamper,
                onEnforcePermissions = viewModel::enforcePermissions,
                onRefreshMdmStatus = viewModel::fetchMdmStatus,
                onDismissMdmMessage = viewModel::dismissMdmMessage,
                onDismissDialogs = viewModel::dismissDialogs,
                onWakeDeviceClick = viewModel::wakeDevice,
                onConsumeRefreshOutcome = viewModel::consumeRefreshOutcome
            )
        }

        composable(AdminRoutes.FILE_BROWSER) { backStackEntry ->
            val rawDeviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
            val deviceId = if (rawDeviceId.contains("_")) rawDeviceId.substringBefore("_") else rawDeviceId
            FileBrowserScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(AdminRoutes.RECORDINGS) {
            val viewModel: com.sentinel.admin.ui.recordings.RecordingsViewModel = hiltViewModel()
            com.sentinel.admin.ui.recordings.RecordingsScreen(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Temporary placeholder screen used during architecture skeleton phase.
 * Replaced with real screens in later phases.
 */
@Composable
private fun PlaceholderScreen(label: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label)
    }
}
