package com.sentinel.admin.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

@Composable
fun MrRobotNetworkIntCard(
    socketEndpointsSummary: String,
    roomHiddenCamSummary: String,
    arpAndDnsGuardSummary: String,
    hardwarePrivacySummary: String,
    ultrasonicSummary: String,
    bleTrackerSummary: String,
    socketPeersDetailText: String = "",
    bleRadarDetailText: String = "",
    onInspect: ((title: String, subtitle: String, fullText: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }

    val anyAlert = listOf(
        socketEndpointsSummary,
        roomHiddenCamSummary,
        arpAndDnsGuardSummary,
        hardwarePrivacySummary,
        ultrasonicSummary,
        bleTrackerSummary
    ).any { it.contains("🚨") }

    val anyWarning = !anyAlert && listOf(
        socketEndpointsSummary,
        roomHiddenCamSummary,
        arpAndDnsGuardSummary,
        hardwarePrivacySummary,
        ultrasonicSummary,
        bleTrackerSummary
    ).any { it.contains("⚠️") }

    val headerBorder = when {
        anyAlert -> Color(0xFFF43F5E).copy(alpha = 0.6f)
        anyWarning -> Color(0xFFFB923C).copy(alpha = 0.6f)
        else -> Color(0xFF1E293B)
    }

    val statusBadgeColor = when {
        anyAlert -> Color(0xFFF43F5E) to Color(0xFF4C0519)
        anyWarning -> Color(0xFFFB923C) to Color(0xFF431407)
        else -> Color(0xFF38BDF8) to Color(0xFF082F49)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E141D)),
        border = BorderStroke(1.dp, headerBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (anyAlert) Color(0xFFF43F5E) else if (anyWarning) Color(0xFFFB923C) else Color(0xFF38BDF8)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "MR. ROBOT NETWORKINT // SIGINT SWEEPER",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFF1F5F9),
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Full-Spectrum RF, Sonic, BLE & Socket Surveillance",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBadgeColor.second,
                    border = BorderStroke(1.dp, statusBadgeColor.first.copy(alpha = 0.4f)),
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (anyAlert) "🚨 THREAT DETECTED" else if (anyWarning) "⚠️ SUSPICIOUS RF" else "SHIELD ACTIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = statusBadgeColor.first,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = statusBadgeColor.first,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))

                // 2x3 Tactical Grid
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Row 1: Sockets & Hotel Cam
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NetworkIntTile(
                            icon = Icons.Default.Hub,
                            label = "KERNEL SOCKET PEERS (getpeername)",
                            summary = socketEndpointsSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "KERNEL SOCKET PEERS (getpeername)",
                                    "Live Linux kernel sockets, p0f OSINT & TCP_INFO",
                                    socketPeersDetailText.ifBlank { socketEndpointsSummary }
                                )
                            }
                        )
                        NetworkIntTile(
                            icon = Icons.Default.Videocam,
                            label = "HOTEL HIDDEN-CAM & OUI SWEEP",
                            summary = roomHiddenCamSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "HOTEL HIDDEN-CAM & OUI SWEEP",
                                    "Scanned local BSSIDs matching surveillance cam OUIs",
                                    roomHiddenCamSummary
                                )
                            }
                        )
                    }

                    // Row 2: ARP/DNS & Hardware Privacy
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NetworkIntTile(
                            icon = Icons.Default.Security,
                            label = "LAN ARP-POISON & DNS GUARD",
                            summary = arpAndDnsGuardSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "LAN ARP-POISON & DNS GUARD",
                                    "Subnet gateway MAC consistency & DoT/DoH transport",
                                    arpAndDnsGuardSummary
                                )
                            }
                        )
                        NetworkIntTile(
                            icon = Icons.Default.Lock,
                            label = "CAM & MIC EAVESDROP GUARD",
                            summary = hardwarePrivacySummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "CAM & MIC EAVESDROP GUARD",
                                    "Hardware camera/microphone daemon session audit",
                                    hardwarePrivacySummary
                                )
                            }
                        )
                    }

                    // Row 3: Ultrasonic & BLE Tracker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NetworkIntTile(
                            icon = Icons.Default.GraphicEq,
                            label = "ULTRASONIC BEACON FFT (18-23kHz)",
                            summary = ultrasonicSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "ULTRASONIC BEACON FFT (18-23kHz)",
                                    "10-bin Goertzel spectral energy analysis (48 kHz)",
                                    ultrasonicSummary
                                )
                            }
                        )
                        NetworkIntTile(
                            icon = Icons.Default.Bluetooth,
                            label = "BLE TRACKER & FLIPPER RADAR",
                            summary = bleTrackerSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "BLE TRACKER & FLIPPER RADAR",
                                    "Nearby Bluetooth Low Energy beacons, AirTags & distance estimates",
                                    bleRadarDetailText.ifBlank { bleTrackerSummary }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun KismetBeaconRadarCard(
    kismet80211Summary: String,
    tlsDpiProbeSummary: String,
    wifiSsid: String,
    wifiBssid: String,
    localIp: String,
    gatewayIp: String,
    dnsServers: String,
    totalTrafficSummary: String,
    nearbyWifiBssids: List<Map<String, Any>>,
    wifiRadarDetailText: String = "",
    onInspect: ((title: String, subtitle: String, fullText: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }

    val anyAlert = kismet80211Summary.contains("🚨") || tlsDpiProbeSummary.contains("🚨")
    val anyWarning = !anyAlert && (kismet80211Summary.contains("⚠️") || tlsDpiProbeSummary.contains("⚠️"))

    val headerBorder = when {
        anyAlert -> Color(0xFFF43F5E).copy(alpha = 0.6f)
        anyWarning -> Color(0xFFFB923C).copy(alpha = 0.6f)
        else -> Color(0xFF1E293B)
    }

    val statusBadgeColor = when {
        anyAlert -> Color(0xFFF43F5E) to Color(0xFF4C0519)
        anyWarning -> Color(0xFFFB923C) to Color(0xFF431407)
        else -> Color(0xFF10B981) to Color(0xFF064E3B)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E141D)),
        border = BorderStroke(1.dp, headerBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (anyAlert) Color(0xFFF43F5E) else if (anyWarning) Color(0xFFFB923C) else Color(0xFF10B981)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "KISMET 802.11 RAW BEACON IE & INDOOR RADAR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFF1F5F9),
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "PHY Frames, QBSS Station Load, 802.11w PMF & DIRECT-* OSINT",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBadgeColor.second,
                    border = BorderStroke(1.dp, statusBadgeColor.first.copy(alpha = 0.4f)),
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (anyAlert) "🚨 PHY ANOMALY" else if (anyWarning) "⚠️ DEAUTH VULN" else "RADAR VERIFIED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = statusBadgeColor.first,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = statusBadgeColor.first,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Tile 1: 802.11 PHY & Beacon IE
                    NetworkIntTile(
                        icon = Icons.Default.Wifi,
                        label = "802.11 PHY & BEACON IE (Kismet / 802.11w PMF / QBSS)",
                        summary = kismet80211Summary,
                        onClick = {
                            onInspect?.invoke(
                                "802.11 PHY & BEACON IE (Kismet Dissector)",
                                "Raw 802.11 frames, QBSS load, PMF 802.11w & WPS TLVs",
                                kismet80211Summary
                            )
                        }
                    )

                    // Tile 2: TLS 1.3 & DPI Middlebox Probe
                    NetworkIntTile(
                        icon = Icons.Default.Lock,
                        label = "TLS 1.3 & DPI MIDDLEBOX PROBE (SSL-Bump Hunter)",
                        summary = tlsDpiProbeSummary,
                        onClick = {
                            onInspect?.invoke(
                                "TLS 1.3 & DPI MIDDLEBOX PROBE",
                                "Active cryptographic probe querying 1.1.1.1:443 for SSL-bump inspection",
                                tlsDpiProbeSummary
                            )
                        }
                    )

                    // Tile 3: Wi-Fi Uplink & Gateway Topology
                    val uplinkSummary = "SSID: $wifiSsid · BSSID: $wifiBssid\nLocal IP: $localIp · Gateway: $gatewayIp\nDNS: $dnsServers · Traffic: $totalTrafficSummary"
                    NetworkIntTile(
                        icon = Icons.Default.Hub,
                        label = "WI-FI UPLINK & GATEWAY TOPOLOGY",
                        summary = uplinkSummary,
                        maxLines = 12,
                        onClick = {
                            onInspect?.invoke(
                                "WI-FI UPLINK & GATEWAY TOPOLOGY",
                                "Network gateway, local IPv4/v6 subnet, DNS & bandwidth counters",
                                uplinkSummary
                            )
                        }
                    )

                    // Tile 4: Indoor Wi-Fi & DIRECT-* Hostname Radar
                    val radarSummary = if (nearbyWifiBssids.isNotEmpty()) {
                        nearbyWifiBssids.take(8).joinToString("\n") { ap ->
                            val s = ap["ssid"]?.toString() ?: "<Hidden>"
                            val b = ap["bssid"]?.toString() ?: "N/A"
                            val r = ap["rssiDbm"]?.toString() ?: "-0"
                            val band = ap["band"]?.toString() ?: "N/A"
                            val dist = ap["distStr"]?.toString() ?: "~?m"
                            val oui = ap["ouiVendor"]?.toString() ?: "IEEE"
                            val ie = ap["ieSummary"]?.toString() ?: ""
                            val wps = ap["wpsModel"]?.toString()?.takeIf { it.isNotBlank() }?.let { " · WPS:$it" } ?: ""
                            "$s -> $b [$band · $oui · $dist · $ie$wps] (${r}dBm)"
                        }
                    } else {
                        "Scanning 2.4 GHz & 5 GHz spectrum for 802.11 Beacons..."
                    }
                    NetworkIntTile(
                        icon = Icons.Default.Sensors,
                        label = "INDOOR WI-FI & DIRECT-* HOSTNAME RADAR (${nearbyWifiBssids.size} APs)",
                        summary = radarSummary,
                        maxLines = 12,
                        onClick = {
                            onInspect?.invoke(
                                "INDOOR WI-FI & DIRECT-* HOSTNAME RADAR",
                                "All surrounding 802.11 access points with ITU path-loss distances & OUIs",
                                wifiRadarDetailText.ifBlank { radarSummary }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun KernelSnmpBasebandSigintCard(
    snmpKernelSummary: String,
    mdnsLanServicesSummary: String,
    cellHandoverSummary: String,
    apkAndHookAuditSummary: String,
    cellTowers: List<Map<String, Any>>,
    mdnsDetailText: String = "",
    apkAuditDetailText: String = "",
    deepRuntimeIntegritySummary: String = "Verifying ELF .text, Binder IPC & Virtual Displays...",
    deepRuntimeDetailText: String = "",
    onInspect: ((title: String, subtitle: String, fullText: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }

    val anyAlert = snmpKernelSummary.contains("🚨") || cellHandoverSummary.contains("🚨") || apkAndHookAuditSummary.contains("🚨") || deepRuntimeIntegritySummary.contains("🚨")
    val anyWarning = !anyAlert && (snmpKernelSummary.contains("⚠️") || cellHandoverSummary.contains("⚠️") || apkAndHookAuditSummary.contains("⚠️") || deepRuntimeIntegritySummary.contains("⚠️"))

    val headerBorder = when {
        anyAlert -> Color(0xFFF43F5E).copy(alpha = 0.6f)
        anyWarning -> Color(0xFFFB923C).copy(alpha = 0.6f)
        else -> Color(0xFF1E293B)
    }

    val statusBadgeColor = when {
        anyAlert -> Color(0xFFF43F5E) to Color(0xFF4C0519)
        anyWarning -> Color(0xFFFB923C) to Color(0xFF431407)
        else -> Color(0xFF818CF8) to Color(0xFF1E1B4B)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E141D)),
        border = BorderStroke(1.dp, headerBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (anyAlert) Color(0xFFF43F5E) else if (anyWarning) Color(0xFFFB923C) else Color(0xFF818CF8)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "KERNEL SNMP MIB-II, PASSIVE mDNS & BASEBAND SIGINT",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFF1F5F9),
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Protocol Stack Counters, ZeroConf Discovery & Baseband Handover Guard",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBadgeColor.second,
                    border = BorderStroke(1.dp, statusBadgeColor.first.copy(alpha = 0.4f)),
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (anyAlert) "🚨 SIGINT ALERT" else if (anyWarning) "⚠️ SIGINT WARN" else "STACK NOMINAL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = statusBadgeColor.first,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = statusBadgeColor.first,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Tile 1: Kernel SNMP Counters
                    NetworkIntTile(
                        icon = Icons.Default.Timeline,
                        label = "KERNEL SNMP MIB-II COUNTERS (/proc/self/net/snmp)",
                        summary = snmpKernelSummary,
                        onClick = {
                            onInspect?.invoke(
                                "KERNEL SNMP MIB-II COUNTERS",
                                "Linux protocol stack counters (/proc/self/net/snmp)",
                                snmpKernelSummary
                            )
                        }
                    )

                    // Tile 2: Passive mDNS / ZeroConf LAN
                    NetworkIntTile(
                        icon = Icons.Default.Hub,
                        label = "PASSIVE mDNS / ZEROCONF LAN DISCOVERY",
                        summary = mdnsLanServicesSummary,
                        maxLines = 12,
                        onClick = {
                            onInspect?.invoke(
                                "PASSIVE mDNS / ZEROCONF LAN DISCOVERY",
                                "Zero-configuration local area network service instances",
                                mdnsDetailText.ifBlank { mdnsLanServicesSummary }
                            )
                        }
                    )

                    // Tile 3: Baseband Handover Guard
                    NetworkIntTile(
                        icon = Icons.Default.Sensors,
                        label = "BASEBAND HANDOVER & CELL-JUMP GUARD",
                        summary = cellHandoverSummary,
                        onClick = {
                            onInspect?.invoke(
                                "BASEBAND HANDOVER & CELL-JUMP GUARD",
                                "Stationary cellular sector tracking & IMSI-catcher tripwires",
                                cellHandoverSummary
                            )
                        }
                    )

                    // Tile 4: APK Supply-Chain & Hook Audit
                    NetworkIntTile(
                        icon = Icons.Default.Security,
                        label = "APK SUPPLY-CHAIN & ACCESSIBILITY/IME HOOK AUDIT",
                        summary = apkAndHookAuditSummary,
                        onClick = {
                            onInspect?.invoke(
                                "APK SUPPLY-CHAIN & ACCESSIBILITY/IME HOOK AUDIT",
                                "Complete per-package inspection: sideload source, X.509 cert SHA-256 & spyware perms",
                                apkAuditDetailText.ifBlank { apkAndHookAuditSummary }
                            )
                        }
                    )

                    // Tile 5: Detected Cell Towers
                    val towersSummary = if (cellTowers.isNotEmpty()) {
                        cellTowers.take(6).joinToString("\n") { tower ->
                            val tType = tower["type"]?.toString() ?: "Cell"
                            val tReg = if (tower["registered"] == true) "Registered" else "Neighbor"
                            val tId = tower["identity"]?.toString() ?: ""
                            val tDbm = tower["dbm"]?.toString() ?: ""
                            "$tType [$tReg] -> $tId ($tDbm dBm)"
                        }
                    } else {
                        "Listening to physical baseband RIL cells..."
                    }
                    NetworkIntTile(
                        icon = Icons.Default.Wifi,
                        label = "DETECTED CELL TOWERS (${cellTowers.size} Towers)",
                        summary = towersSummary,
                        maxLines = 12,
                        onClick = {
                            onInspect?.invoke(
                                "DETECTED CELL TOWERS",
                                "Physical baseband RIL serving and neighbor cell towers",
                                towersSummary
                            )
                        }
                    )

                    // Tile 6: ELF .text, Binder IPC, Screen-Tap & Exfil Guard
                    NetworkIntTile(
                        icon = Icons.Default.Shield,
                        label = "ELF .TEXT, BINDER IPC, SCREEN-TAP & EXFIL GUARD",
                        summary = deepRuntimeIntegritySummary,
                        onClick = {
                            onInspect?.invoke(
                                "ELF .TEXT, BINDER IPC & RUNTIME INTEGRITY",
                                "Deep runtime verification of ELF memory, Binder IPC proxies, and covert screen taps",
                                deepRuntimeDetailText.ifBlank { deepRuntimeIntegritySummary }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun OmegaTradecraftDarkSeaCard(
    siliconJniSummary: String,
    siliconJniDetailText: String = "",
    faradayMerkleSummary: String,
    faradayMerkleDetailText: String = "",
    wifiMotionRadarSummary: String,
    wifiMotionDetailText: String = "",
    evilMaidSummary: String,
    evilMaidDetailText: String = "",
    stingray3gppSummary: String,
    stingray3gppDetailText: String = "",
    zeroClickSurfaceSummary: String,
    zeroClickDetailText: String = "",
    onInspect: ((title: String, subtitle: String, fullText: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }

    val anyAlert = siliconJniSummary.contains("🚨") ||
            faradayMerkleSummary.contains("🚨") ||
            wifiMotionRadarSummary.contains("🚨") ||
            evilMaidSummary.contains("🚨") ||
            stingray3gppSummary.contains("🚨") ||
            zeroClickSurfaceSummary.contains("🚨")

    val anyWarning = !anyAlert && (
            siliconJniSummary.contains("⚠️") ||
            faradayMerkleSummary.contains("⚠️") ||
            wifiMotionRadarSummary.contains("⚠️") ||
            evilMaidSummary.contains("⚠️") ||
            stingray3gppSummary.contains("⚠️") ||
            zeroClickSurfaceSummary.contains("⚠️")
    )

    val headerBorder = when {
        anyAlert -> Color(0xFFF43F5E).copy(alpha = 0.6f)
        anyWarning -> Color(0xFFFB923C).copy(alpha = 0.6f)
        else -> Color(0xFF1E293B)
    }

    val statusBadgeColor = when {
        anyAlert -> Color(0xFFF43F5E) to Color(0xFF4C0519)
        anyWarning -> Color(0xFFFB923C) to Color(0xFF431407)
        else -> Color(0xFF818CF8) to Color(0xFF1E1B4B)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E141D)),
        border = BorderStroke(1.dp, headerBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (anyAlert) Color(0xFFF43F5E) else if (anyWarning) Color(0xFFFB923C) else Color(0xFF818CF8)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "OMEGA DARK-MATTER // SUB-ATOMIC SILICON & DARKNET SWEEPER",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFF1F5F9),
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Hardware Physics Engine, Quantum Entropy, Merkle Ledger & Dark-Web Hunter",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBadgeColor.second,
                    border = BorderStroke(1.dp, statusBadgeColor.first.copy(alpha = 0.4f)),
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (anyAlert) "🚨 DEFCON 1 BREACH" else if (anyWarning) "⚠️ THREAT DETECTED" else "PHYSICS NOMINAL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = statusBadgeColor.first,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = statusBadgeColor.first,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))

                // 2x3 Tactical Grid
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Row 1: ARM64 CNTVCT & PMIC Coulomb Merkle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NetworkIntTile(
                            icon = Icons.Default.Bolt,
                            label = "ARM64 CNTVCT, PAC/BTI & QUANTUM H(X)",
                            summary = siliconJniSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "ARM64 SILICON, PAC/BTI & QUANTUM H(X)",
                                    "ARM64 cntvct_el0 ISB pipeline timing, /dev/urandom Shannon entropy & JNI vtable",
                                    siliconJniDetailText.ifBlank { siliconJniSummary }
                                )
                            }
                        )
                        NetworkIntTile(
                            icon = Icons.Default.BatteryChargingFull,
                            label = "PMIC ELECTRON DRAIN & MERKLE LOG",
                            summary = faradayMerkleSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "PMIC ELECTRON DRAIN & SHA-256 MERKLE LOG",
                                    "Hardware battery coulomb counter (uAh) & cryptographic hash-chained audit ledger",
                                    faradayMerkleDetailText.ifBlank { faradayMerkleSummary }
                                )
                            }
                        )
                    }

                    // Row 2: Wi-Fi Multipath & Evil-Maid Trap
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NetworkIntTile(
                            icon = Icons.Default.Wifi,
                            label = "WI-FI MULTIPATH & DARKNET TOR HUNTER",
                            summary = wifiMotionRadarSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "WI-FI MULTIPATH & DARKNET TOR HUNTER",
                                    "Sub-carrier Doppler RF motion detection & Dark-Web Tor/I2P node sweeper",
                                    wifiMotionDetailText.ifBlank { wifiMotionRadarSummary }
                                )
                            }
                        )
                        NetworkIntTile(
                            icon = Icons.Default.Lock,
                            label = "EVIL-MAID & HARD-IRON CHASSIS TRAP",
                            summary = evilMaidSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "EVIL-MAID & HARD-IRON CHASSIS TRAP",
                                    "Uncalibrated magnetometer bias, pneumatic acoustic delta & physical intrusion logs",
                                    evilMaidDetailText.ifBlank { evilMaidSummary }
                                )
                            }
                        )
                    }

                    // Row 3: 3GPP Stingray & Zero-Click Kernel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NetworkIntTile(
                            icon = Icons.Default.NetworkCell,
                            label = "3GPP RSRP/SINR/TA STINGRAY RANGE",
                            summary = stingray3gppSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "3GPP RSRP/SINR/TA STINGRAY RANGE",
                                    "3GPP physical radio layer, LTE/5G timing advance & IMSI-catcher distance",
                                    stingray3gppDetailText.ifBlank { stingray3gppSummary }
                                )
                            }
                        )
                        NetworkIntTile(
                            icon = Icons.Default.Shield,
                            label = "ZERO-CLICK WEBVIEW & KERNEL SYSCTL",
                            summary = zeroClickSurfaceSummary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onInspect?.invoke(
                                    "ZERO-CLICK WEBVIEW & KERNEL SYSCTL",
                                    "System WebView Blink/V8 CVE audit, kernel sysctl kptr/dmesg & OPSEC score",
                                    zeroClickDetailText.ifBlank { zeroClickSurfaceSummary }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkIntTile(
    icon: ImageVector,
    label: String,
    summary: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 5,
    onClick: (() -> Unit)? = null
) {
    val isAlert = summary.contains("🚨")
    val isWarning = !isAlert && summary.contains("⚠️")

    val (cardBg, borderCol, accentCol, textCol) = when {
        isAlert -> QuadColor(Color(0xFF3F0A14), Color(0xFFF43F5E).copy(alpha = 0.5f), Color(0xFFF87171), Color(0xFFFECDD3))
        isWarning -> QuadColor(Color(0xFF3B1A0A), Color(0xFFFB923C).copy(alpha = 0.5f), Color(0xFFFB923C), Color(0xFFFED7AA))
        else -> QuadColor(Color(0xFF080D14), Color(0xFF1E293B), Color(0xFF38BDF8), Color(0xFFE2E8F0))
    }

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 76.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderCol)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
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
                        tint = accentCol,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAlert || isWarning) accentCol else Color(0xFF64748B),
                        letterSpacing = 0.4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onClick != null) {
                    Text(
                        text = "FORENSICS ›",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8).copy(alpha = 0.7f),
                        letterSpacing = 0.3.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = textCol,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.5.sp,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TacticalForensicsInspectorDialog(
    title: String,
    subtitle: String,
    fullText: String,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var showFlaggedOnly by remember { mutableStateOf(false) }

    val allLines = remember(fullText) { fullText.lines() }
    val hasFlaggedLines = remember(allLines) {
        allLines.any { it.contains("🚨") || it.contains("⚠️") || it.contains("SIDELOAD") || it.contains("DEBUG") }
    }
    val displayedLines = remember(allLines, showFlaggedOnly) {
        if (!showFlaggedOnly) allLines
        else {
            val filtered = mutableListOf<String>()
            var includeNext = false
            for (line in allLines) {
                if (line.startsWith("===") || line.startsWith("---")) {
                    filtered.add(line)
                    includeNext = false
                } else if (line.contains("🚨") || line.contains("⚠️") || line.contains("SIDELOAD") || line.contains("DEBUG")) {
                    filtered.add(line)
                    includeNext = true
                } else if (includeNext && line.trimStart().startsWith("↳")) {
                    filtered.add(line)
                    includeNext = false
                } else {
                    includeNext = false
                }
            }
            filtered
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 20.dp)
            .border(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
        containerColor = Color(0xFF0B111A),
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFF1F5F9),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 11.5.sp, color = Color(0xFF94A3B8))

                if (hasFlaggedLines) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !showFlaggedOnly,
                            onClick = { showFlaggedOnly = false },
                            label = { Text("ALL TELEMETRY", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        FilterChip(
                            selected = showFlaggedOnly,
                            onClick = { showFlaggedOnly = true },
                            label = { Text("⚠️ FLAGGED / SIDELOADED ONLY", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            }
        },
        text = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 540.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF06090E),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    displayedLines.forEach { line ->
                        val isAlert = line.contains("🚨")
                        val isWarning = !isAlert && (line.contains("⚠️") || line.contains("SIDELOAD") || line.contains("DEBUG-CERT"))
                        val isHeader = line.startsWith("===") || line.startsWith("---")
                        val isBullet = line.startsWith("•")

                        val lineCol = when {
                            isAlert -> Color(0xFFF87171)
                            isWarning -> Color(0xFFFB923C)
                            isHeader -> Color(0xFF38BDF8)
                            isBullet -> Color(0xFFF1F5F9)
                            else -> Color(0xFFCBD5E1)
                        }
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (isHeader) 13.5.sp else 12.8.sp,
                            fontWeight = if (isHeader || isBullet || isAlert || isWarning) FontWeight.Bold else FontWeight.Medium,
                            lineHeight = 18.5.sp,
                            color = lineCol
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(fullText))
                    copied = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (copied) Color(0xFF059669) else Color(0xFF0284C7))
            ) {
                Icon(imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = if (copied) "COPIED" else "Copy Report", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(text = "Close", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFCBD5E1))
            }
        }
    )
}

private data class QuadColor(val bg: Color, val border: Color, val accent: Color, val text: Color)
