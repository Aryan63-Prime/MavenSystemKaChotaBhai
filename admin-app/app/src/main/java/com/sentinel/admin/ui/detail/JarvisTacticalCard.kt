package com.sentinel.admin.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinel.admin.ui.theme.*

@Composable
fun JarvisTacticalCard(
    defconLevel: Int,
    defconSummary: String?,
    mlModelStatus: String?,
    predictedInterceptSummary: String?,
    compassAzimuthDeg: Int?,
    devicePoseAndCameraHint: String?,
    estimatedAltitudeMeters: Double?,
    estimatedFloor: Int?,
    verticalMotionState: String?,
    ambientEnvironment: String?,
    magneticFieldUt: Int?,
    emfAnomalyDetected: Boolean,
    seismicVibrationSummary: String?,
    gnssConstellationSummary: String?,
    batteryEnduranceSummary: String?,
    cpuFrequencySummary: String?,
    vpnActive: Boolean,
    totalTrafficSummary: String?,
    activeTcpSockets: Int?,
    usbHardwareSummary: String?,
    usbIntrusionSuspected: Boolean,
    simSecuritySummary: String?,
    nearestUnitSummary: String?,
    servingCellSummary: String?,
    gaussmeterSummary: String? = null,
    apkAndHookAuditSummary: String? = null,
    apkAuditDetailText: String = "",
    onInspect: ((title: String, subtitle: String, fullText: String) -> Unit)? = null,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }

    val (defconColor, defconBg) = when (defconLevel) {
        1 -> Color(0xFFF43F5E) to Color(0xFF4C0519)
        2 -> Color(0xFFFB923C) to Color(0xFF431407)
        3 -> Color(0xFFFBBF24) to Color(0xFF451A03)
        4 -> Color(0xFF38BDF8) to Color(0xFF082F49)
        else -> Color(0xFF34D399) to Color(0xFF064E3B)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E141D)),
        border = BorderStroke(
            1.dp,
            if (defconLevel <= 2 || usbIntrusionSuspected) Color(0xFFF43F5E).copy(alpha = 0.6f) else Color(0xFF1E293B)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Title on Left (weighted), Non-wrapping DEFCON Badge on Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF38BDF8).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TACTICAL C4ISR FLEET HUD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = Color(0xFF38BDF8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (!defconSummary.isNullOrBlank()) defconSummary else "Full-Spectrum Sensor & Kinematic Telemetry",
                            fontSize = 11.sp,
                            color = if (defconLevel <= 2) Color(0xFFFCA5A5) else Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Fixed non-wrapping DEFCON Badge + Expand icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = defconBg,
                        border = BorderStroke(1.dp, defconColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "DEFCON $defconLevel",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = defconColor,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))

                // Section 1: Arc-Reactor Compass & 3D Attitude Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF080D14),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Iron Man Arc-Reactor Compass Dial (62dp)
                        Box(
                            modifier = Modifier.size(62.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val azimuth = compassAzimuthDeg ?: 0
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val r = size.minDimension / 2f - 3f

                                // Outer reactor ring
                                drawCircle(
                                    color = Color(0xFF1E293B),
                                    radius = r,
                                    center = c,
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                                // Inner glow ring
                                drawCircle(
                                    color = Color(0xFF0284C7).copy(alpha = 0.25f),
                                    radius = r * 0.72f,
                                    center = c,
                                    style = Stroke(width = 1.dp.toPx())
                                )

                                // 4 Cardinal pips (N in crimson, E/S/W in slate)
                                val pipLen = 4.dp.toPx()
                                drawLine(Color(0xFFEF4444), Offset(c.x, c.y - r), Offset(c.x, c.y - r + pipLen + 2.dp.toPx()), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                                drawLine(Color(0xFF64748B), Offset(c.x + r - pipLen, c.y), Offset(c.x + r, c.y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                                drawLine(Color(0xFF64748B), Offset(c.x, c.y + r - pipLen), Offset(c.x, c.y + r), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                                drawLine(Color(0xFF64748B), Offset(c.x - r, c.y), Offset(c.x - r + pipLen, c.y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)

                                // Dynamic heading needle pointing at azimuth
                                val rad = Math.toRadians(azimuth.toDouble() - 90.0)
                                val needleEnd = Offset(
                                    (c.x + (r - 6.dp.toPx()) * Math.cos(rad)).toFloat(),
                                    (c.y + (r - 6.dp.toPx()) * Math.sin(rad)).toFloat()
                                )
                                drawLine(
                                    color = Color(0xFF38BDF8),
                                    start = c,
                                    end = needleEnd,
                                    strokeWidth = 3.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                // Glowing reactor core
                                drawCircle(color = Color(0xFF0284C7).copy(alpha = 0.4f), radius = 6.dp.toPx(), center = c)
                                drawCircle(color = Color(0xFF38BDF8), radius = 3.dp.toPx(), center = c)
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${compassAzimuthDeg ?: 0}° Azimuth",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8),
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val floorText = when {
                                    estimatedFloor != null -> "Floor $estimatedFloor"
                                    estimatedAltitudeMeters != null -> "%.1fm Alt".format(estimatedAltitudeMeters)
                                    else -> "Floor N/A (No Barometer)"
                                }
                                Text(
                                    text = floorText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = devicePoseAndCameraHint ?: "Orientation Calibrating",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF1F5F9),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = verticalMotionState ?: "LEVEL ALTITUDE (0.0 m/s)",
                                fontSize = 10.sp,
                                color = Color(0xFF64748B),
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Section 2: 15-Minute Kalman Intercept
                if (!predictedInterceptSummary.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0C1929),
                        border = BorderStroke(1.dp, Color(0xFF1E3A8A).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("15m AI Intercept Trajectory", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF93C5FD))
                                Text(predictedInterceptSummary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFF0F9FF))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Section 3: 2-Column Telemetry Grid (Unclipped, Dynamic Height)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HudMetricTile(
                            icon = Icons.Default.WbSunny,
                            label = "AMBIENT LUX",
                            value = ambientEnvironment?.substringBefore("(") ?: "Normal",
                            subValue = "${magneticFieldUt ?: 45} µT EMF",
                            isAlert = emfAnomalyDetected,
                            modifier = Modifier.weight(1f)
                        )
                        HudMetricTile(
                            icon = Icons.Default.Vibration,
                            label = "SEISMIC TREMOR",
                            value = seismicVibrationSummary?.substringBefore("(") ?: "DEAD STILL",
                            subValue = seismicVibrationSummary?.substringAfter("(")?.substringBefore(")") ?: "0 mG",
                            isAlert = (seismicVibrationSummary?.contains("HANDLING") == true),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HudMetricTile(
                            icon = Icons.Default.Usb,
                            label = "USB-C PORT",
                            value = if (usbIntrusionSuspected) "INTRUSION" else "PORT CLEAN",
                            subValue = usbHardwareSummary?.take(26) ?: "No OTG",
                            isAlert = usbIntrusionSuspected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "HARDWARE USB-C PORT MONITOR",
                                    "Physical host/peripheral integrity & locked port access",
                                    usbHardwareSummary ?: "Port Idle / No Peripherals"
                                )
                            }
                        )
                        HudMetricTile(
                            icon = Icons.Default.SimCard,
                            label = "SIM / SIGINT",
                            value = simSecuritySummary?.substringBefore("(") ?: "Verified",
                            subValue = servingCellSummary?.take(26) ?: "5G-NR",
                            isAlert = (simSecuritySummary?.contains("Mismatch", ignoreCase = true) == true),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "BASEBAND CELLULAR & SIM SIGINT",
                                    "RIL cell towers & encryption status",
                                    "${simSecuritySummary ?: "Verified Secure"}\nServing Cell: ${servingCellSummary ?: "Cellular Standby"}"
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HudMetricTile(
                            icon = Icons.Default.Speed,
                            label = "CPU & BATTERY",
                            value = cpuFrequencySummary?.take(26) ?: "8 Cores",
                            subValue = batteryEnduranceSummary?.take(26) ?: "Nominal",
                            isAlert = false,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "HARDWARE CPU & BATTERY METRICS",
                                    "Frequency scaling & discharge curves",
                                    "${cpuFrequencySummary ?: "CPU Nominal"}\n${batteryEnduranceSummary ?: "Battery Nominal"}"
                                )
                            }
                        )
                        HudMetricTile(
                            icon = Icons.Default.Sensors,
                            label = "3D GAUSSMETER & BUG SWEEP",
                            value = gaussmeterSummary?.substringBefore("(")?.trim()?.take(24) ?: "Nominal Earth Field",
                            subValue = (if (gaussmeterSummary?.contains("(") == true) "(" + gaussmeterSummary.substringAfter("(") else "∇B=0.0 µT · Clean").take(28),
                            isAlert = gaussmeterSummary?.contains("🚨") == true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "3D GAUSSMETER & RF BUG SWEEP",
                                    "Tri-axis MEMS magnetometer flux gradient",
                                    gaussmeterSummary ?: "Nominal Earth Field"
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HudMetricTile(
                            icon = Icons.Default.Security,
                            label = "APK & HOOK SHIELD",
                            value = apkAndHookAuditSummary?.substringBefore("(")?.trim()?.take(24) ?: "Verified Clean",
                            subValue = (if (apkAndHookAuditSummary?.contains("(") == true) "(" + apkAndHookAuditSummary.substringAfter("(") else "0 Ghosts · Validated").take(28),
                            isAlert = apkAndHookAuditSummary?.contains("🚨") == true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "APK SUPPLY-CHAIN & ACCESSIBILITY/IME HOOK AUDIT",
                                    "Complete per-package inspection: sideload source, X.509 cert SHA-256 & spyware perms",
                                    apkAuditDetailText.ifBlank { apkAndHookAuditSummary ?: "Clean" }
                                )
                            }
                        )
                        HudMetricTile(
                            icon = Icons.Default.Public,
                            label = "GNSS SATELLITES",
                            value = gnssConstellationSummary?.substringBefore("(") ?: "0 Locked",
                            subValue = gnssConstellationSummary?.substringAfter("(", "")?.substringBeforeLast(")", "")?.ifBlank { "Scanning sky…" } ?: "Scanning sky…",
                            isAlert = false,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "GNSS SATELLITE CONSTELLATIONS",
                                    "Real-time multi-constellation locks (GPS/BeiDou/GLONASS/Galileo/QZSS)",
                                    gnssConstellationSummary ?: "GNSS Standby"
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HudMetricTile(
                            icon = Icons.Default.NearMe,
                            label = "FLEET PROXIMITY",
                            value = nearestUnitSummary?.substringBefore("(") ?: "Fleet Isolated",
                            subValue = nearestUnitSummary?.substringAfter("(", "")?.substringBeforeLast(")", "")?.ifBlank { "No units <15km" } ?: "No units <15km",
                            isAlert = false,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "FLEET TACTICAL PROXIMITY",
                                    "Nearby Sentinel fleet unit ranges & bearings",
                                    nearestUnitSummary ?: "Fleet Isolated"
                                )
                            }
                        )
                        HudMetricTile(
                            icon = Icons.Default.Lan,
                            label = "TCP TRAFFIC / VPN",
                            value = if (vpnActive) "🔒 VPN ACTIVE" else "Direct Route",
                            subValue = if (!totalTrafficSummary.isNullOrBlank() && totalTrafficSummary != "N/A") {
                                "$totalTrafficSummary${if (activeTcpSockets != null) " (${activeTcpSockets}s)" else ""}"
                            } else {
                                "${activeTcpSockets ?: 0} TCP Sockets"
                            },
                            isAlert = false,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "TCP TRAFFIC & VPN TRANSPORT",
                                    "Kernel TCP sockets & cumulative payload traffic",
                                    "Traffic: ${totalTrafficSummary ?: "N/A"}\nActive Sockets: ${activeTcpSockets ?: 0}\nVPN Encrypted: $vpnActive"
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action: View Full Deep System Diagnostics Terminal
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenDiagnostics() },
                    color = Color(0xFF131D2A),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Deep Hardware Diagnostics Terminal ›",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HudMetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String,
    isAlert: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 68.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = if (isAlert) Color(0xFF3F0A14) else Color(0xFF080D14),
        border = BorderStroke(1.dp, if (isAlert) Color(0xFFF43F5E).copy(alpha = 0.5f) else Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isAlert) Color(0xFFF87171) else Color(0xFF64748B),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAlert) Color(0xFFF87171) else Color(0xFF64748B),
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onClick != null) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Inspect",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isAlert) Color(0xFFFECDD3) else Color(0xFFF1F5F9),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subValue,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = Color(0xFF94A3B8),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
