package com.sentinel.admin.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sentinel.admin.ui.theme.FigmaAccentAmber
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaAccentGreen
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaScreenBackground
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary

/**
 * Sensor History modal dialog aligned with Figma concept:
 * "Concept / 08 / Battery, signal & G-force".
 *
 * Implements Work Orders 3 & 4:
 * - Truthful representation: Host app and backend only provide single-point latest snapshots.
 * - Removed synthetic production curves masquerading as real telemetry history.
 * - Displays actual current readings with clear status.
 * - Reports the exact missing dependency (backend time-series storage endpoint).
 */
@Composable
fun SensorHistoryDialog(
    deviceName: String,
    currentBattery: Int? = null,
    signalDbm: Int? = null,
    accelerationG: Float? = null,
    isOnline: Boolean = true,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = FigmaScreenBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Top close action row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Sensor telemetry",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = FigmaTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$deviceName · Current status",
                            fontSize = 13.sp,
                            color = FigmaTextMuted
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = FigmaTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Truthful dependency banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141E28)),
                    border = BorderStroke(1.dp, Color(0xFF23364A))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = FigmaAccentBlue,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                text = "Historical Telemetry Unavailable",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = FigmaAccentBlue
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Host app and server maintain real-time snapshots only. Historical time-series telemetry logging requires a backend persistence service.",
                                fontSize = 12.sp,
                                color = FigmaTextMuted,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Card 1: Battery
                val batteryText = when {
                    currentBattery == null -> "Unavailable"
                    currentBattery in 0..100 -> "$currentBattery%" + if (!isOnline) " · Stale" else ""
                    else -> "Unavailable"
                }
                SensorSnapshotCard(
                    title = "Battery level",
                    valueText = batteryText,
                    valueColor = FigmaAccentGreen,
                    statusText = if (currentBattery != null) "Latest reading from device" else "No telemetry received"
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Card 2: Network signal
                val signalText = if (signalDbm != null) "$signalDbm dBm" else "Unavailable"
                SensorSnapshotCard(
                    title = "Network signal",
                    valueText = signalText,
                    valueColor = FigmaAccentBlue,
                    statusText = "Host does not currently stream signal dBm"
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Card 3: Acceleration
                val motionText = if (accelerationG != null) {
                    String.format(java.util.Locale.US, "%.2f G", accelerationG)
                } else {
                    "Unavailable"
                }
                SensorSnapshotCard(
                    title = "Acceleration magnitude",
                    valueText = motionText,
                    valueColor = FigmaAccentAmber,
                    statusText = "Continuous accelerometer stream requires backend endpoint"
                )
            }
        }
    }
}

@Composable
private fun SensorSnapshotCard(
    title: String,
    valueText: String,
    valueColor: Color,
    statusText: String
) {
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
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )

                Text(
                    text = valueText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = valueColor
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = statusText,
                fontSize = 12.sp,
                color = FigmaTextMuted
            )
        }
    }
}
