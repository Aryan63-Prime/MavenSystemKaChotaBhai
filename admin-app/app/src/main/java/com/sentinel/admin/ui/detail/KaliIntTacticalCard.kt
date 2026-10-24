package com.sentinel.admin.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dedicated C4ISR Tactical View for Project Sentinel's KaliInt Suite.
 *
 * Visualizes the 8 modernized, mobile-native security and SIGINT tools
 * replacing legacy desktop Kali Linux tools on Android 14–16 (GKI 2.0 / ARM64).
 */
@Composable
fun KaliIntTacticalCard(
    kismet80211Summary: String,
    stingray3gppSummary: String,
    bleTrackerSummary: String,
    tlsDpiProbeSummary: String,
    siliconJniSummary: String,
    deepRuntimeIntegritySummary: String,
    apkAndHookAuditSummary: String,
    faradayMerkleSummary: String = "PMIC Nominal · Merkle Ledger Active",
    wifiRadarDetailText: String = "",
    stingray3gppDetailText: String = "",
    bleRadarDetailText: String = "",
    socketPeersDetailText: String = "",
    siliconJniDetailText: String = "",
    deepRuntimeDetailText: String = "",
    apkAuditDetailText: String = "",
    faradayMerkleDetailText: String = "",
    defconLevel: Int = 5,
    onInspect: ((title: String, subtitle: String, fullText: String) -> Unit)? = null,
    onTriggerBleSpam: ((durationSec: Int) -> Unit)? = null,
    onStopBleSpam: (() -> Unit)? = null,
    isBleSpamming: Boolean = false,
    bleSpamRemainingSec: Int = 0,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }

    val toolSummaries = listOf(
        kismet80211Summary,
        stingray3gppSummary,
        bleTrackerSummary,
        tlsDpiProbeSummary,
        siliconJniSummary,
        deepRuntimeIntegritySummary,
        apkAndHookAuditSummary,
        faradayMerkleSummary
    )

    val anyAlert = toolSummaries.any { it.contains("🚨") || it.contains("Rogue") || it.contains("VIOLATION") }
    val anyWarning = !anyAlert && toolSummaries.any { it.contains("⚠️") || it.contains("Suspicious") || it.contains("Degraded") }

    val headerBorder = when {
        anyAlert -> Color(0xFFF43F5E).copy(alpha = 0.7f)
        anyWarning -> Color(0xFFFB923C).copy(alpha = 0.7f)
        else -> Color(0xFF38BDF8).copy(alpha = 0.4f)
    }

    val statusBadgeColors = when {
        anyAlert -> Color(0xFFF43F5E) to Color(0xFF4C0519)
        anyWarning -> Color(0xFFFB923C) to Color(0xFF431407)
        else -> Color(0xFF34D399) to Color(0xFF064E3B)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1017)),
        border = BorderStroke(1.dp, headerBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusBadgeColors.first)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "KALI-INT // MOBILE-NATIVE SENTINEL",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFF1F5F9),
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF0284C7).copy(alpha = 0.25f),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "GKI 2.0 · RUST/C++ ARM64",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF38BDF8),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "8/8 Sentinels Active",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBadgeColors.second,
                    border = BorderStroke(1.dp, statusBadgeColors.first.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (anyAlert) "THREAT DETECTED" else if (anyWarning) "DEFCON $defconLevel ADVISORY" else "ALL SYSTEMS NOMINAL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = statusBadgeColors.first,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = statusBadgeColors.first,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))

                // Modernization Architectural Statement Banner
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF131D2A),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Desktop Kali Linux attack vectors (monitor-mode frame injection, unsigned .ko RAM dumpers, cross-process ptrace) are blocked by modern Android chipsets and GKI 2.0. KaliInt transforms these vectors into ground-truth hardware-level SIGINT, RF radar, and in-process memory audit sentinels.",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // The 7 KaliInt Tools List
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Tool 1: Wi-Fi Beacon IE Dissector
                    KaliToolItem(
                        icon = Icons.Default.Wifi,
                        title = "RF & WI-FI BEACON IE DISSECTOR",
                        replacesLabel = "Aircrack-ng / Wifite Deauth Storms",
                        advancementBadge = "IEEE 802.11 IE LITTLE-ENDIAN",
                        summaryText = kismet80211Summary,
                        detailSpecs = "RSN IE 48 (802.11w PMF MFPC/MFPR) · QBSS IE 11 (staCount / Load) · WPS TLV 0x1021/0x1023 · Evil-Twin BSSID Correlator",
                        accentColor = Color(0xFF38BDF8),
                        onClick = {
                            onInspect?.invoke(
                                "RF & WI-FI BEACON IE DISSECTOR",
                                "Passive 802.11 Beacon Frame Dissection & Evil-Twin Radar (Replacing Aircrack-ng)",
                                wifiRadarDetailText.ifBlank { kismet80211Summary }
                            )
                        }
                    )

                    // Tool 2: 3GPP 5G-NR & LTE Physical-Layer Radar
                    KaliToolItem(
                        icon = Icons.Default.NetworkCell,
                        title = "3GPP 5G-NR & LTE PHYSICAL-LAYER RADAR",
                        replacesLabel = "OpenBTS / Fake 2G Base Station",
                        advancementBadge = "TA RANGING & RSRP BOUNDS",
                        summaryText = stingray3gppSummary,
                        detailSpecs = "3GPP TS 38.215 / 36.214 · Timing Advance TA × 78.12m Ranging · RSRP [-140..-25 dBm] Validation · IMSI-Catcher Rogue Guard",
                        accentColor = Color(0xFF34D399),
                        onClick = {
                            onInspect?.invoke(
                                "3GPP 5G-NR & LTE PHYSICAL-LAYER RADAR",
                                "Dual-Stack 5G/4G Cell Measurement & Baseband Integrity (Replacing OpenBTS)",
                                stingray3gppDetailText.ifBlank { stingray3gppSummary }
                            )
                        }
                    )

                    // Tool 3: BLE Continuity Sub-TLV & Flipper Radar
                    KaliToolItem(
                        icon = Icons.Default.Bluetooth,
                        title = "BLE CONTINUITY & FLIPPER ZERO RADAR",
                        replacesLabel = "BtleJack / Pairing Flooder",
                        advancementBadge = "HCI 0x7F FILTER & SUB-TLV",
                        summaryText = bleTrackerSummary,
                        detailSpecs = "HCI 0x7F (+127 dBm) Clamp · Apple Continuity 0x12 0x19 AirTag Separation · Flipper Zero Continuity Flood Detection",
                        accentColor = Color(0xFFA78BFA),
                        onClick = {
                            onInspect?.invoke(
                                "BLE CONTINUITY & FLIPPER ZERO RADAR",
                                "Passive BLE Sub-TLV Dissection & Proximity Ranging (Replacing BtleJack)",
                                bleRadarDetailText.ifBlank { bleTrackerSummary }
                            )
                        }
                    )

                    // Tactical Outbound BLE Jammer / Flooder Action
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isBleSpamming) Color(0xFF3B1219) else Color(0xFF131826),
                        border = BorderStroke(
                            1.dp,
                            if (isBleSpamming) Color(0xFFF43F5E) else Color(0xFFA78BFA).copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isBleSpamming) Color(0xFFF43F5E) else Color(0xFFA78BFA),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (isBleSpamming) "🚨 BLE JAMMER / FLOODER ACTIVE" else "OPTIONAL TACTICAL TRANSMITTER",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isBleSpamming) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
                                        )
                                        Text(
                                            text = if (isBleSpamming)
                                                "Transmitting on 37/38/39 · Auto-cutoff in ${bleSpamRemainingSec}s"
                                            else
                                                "Cycles Apple 0x004C, FastPair 0xFE2C & SwiftPair (30s Hard Cap)",
                                            fontSize = 8.5.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (isBleSpamming) {
                                            onStopBleSpam?.invoke()
                                        } else {
                                            onTriggerBleSpam?.invoke(30)
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isBleSpamming) Color(0xFFE11D48) else Color(0xFF7C3AED)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text(
                                        text = if (isBleSpamming) "DISENGAGE (${bleSpamRemainingSec}s)" else "ENGAGE FLOODER",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Tool 4: Dual-Stack Wire TCP_INFO Speed-of-Light MITM
                    KaliToolItem(
                        icon = Icons.Default.Hub,
                        title = "DUAL-STACK WIRE TCP_INFO MITM INSPECTOR",
                        replacesLabel = "Bettercap / mitm6 DHCPv6/LLMNR",
                        advancementBadge = "SOL RTT < 2.5ms & MSS CHECK",
                        summaryText = tlsDpiProbeSummary,
                        detailSpecs = "Wire-Level getsockopt(TCP_INFO) · Speed-of-Light RTT Anomaly (< 2.5ms over WAN) · MSS Clamping (< 1390B) · SYN/ACK Delta",
                        accentColor = Color(0xFFFBBF24),
                        onClick = {
                            onInspect?.invoke(
                                "DUAL-STACK WIRE TCP_INFO MITM INSPECTOR",
                                "Kernel TCP_INFO Metrics & In-Line Proxy Detection (Replacing Bettercap/mitm6)",
                                socketPeersDetailText.ifBlank { tlsDpiProbeSummary }
                            )
                        }
                    )

                    // Tool 5: In-Process W^X & libc.so memcmp Sentinel
                    KaliToolItem(
                        icon = Icons.Default.Memory,
                        title = "IN-PROCESS W^X MEMORY & BIONIC LIBC SENTINEL",
                        replacesLabel = "LiME .ko RAM Bit-Stream Dumper",
                        advancementBadge = "PAGE-TABLE & ELF .TEXT HASH",
                        summaryText = siliconJniSummary,
                        detailSpecs = "Self-VMA rwxp Zero-Tolerance · Fileless /memfd: Exclusion · Bionic libc.so .text On-Disk vs Memory memcmp · ARM64 CNTVCT_EL0 ISB",
                        accentColor = Color(0xFFF43F5E),
                        onClick = {
                            onInspect?.invoke(
                                "IN-PROCESS W^X MEMORY & BIONIC LIBC SENTINEL",
                                "User-Space Memory Integrity & Anti-Tamper Engine (Replacing LiME .ko)",
                                siliconJniDetailText.ifBlank { siliconJniSummary }
                            )
                        }
                    )

                    // Tool 6: ART VM ArtMethod & JNI Vtable Sentinel
                    KaliToolItem(
                        icon = Icons.Default.BugReport,
                        title = "ART VM ARTMETHOD & JNI VTABLE SENTINEL",
                        replacesLabel = "Frida / Xposed Cross-Process Hooks",
                        advancementBadge = "dladdr POINTER & MODIFIER FLIP",
                        summaryText = deepRuntimeIntegritySummary,
                        detailSpecs = "ArtMethod entrypoint_from_quick_compiled_code_ Verification · JNIEnv JNINativeInterface Pointer dladdr Audit · SECCOMP Filter Check",
                        accentColor = Color(0xFFE879F9),
                        onClick = {
                            onInspect?.invoke(
                                "ART VM ARTMETHOD & JNI VTABLE SENTINEL",
                                "DEX Bytecode & Native JNI Vtable Integrity Guard (Replacing Frida/Xposed)",
                                deepRuntimeDetailText.ifBlank { deepRuntimeIntegritySummary }
                            )
                        }
                    )

                    // Tool 7: APK ZipFile Dissection & Shannon Entropy
                    KaliToolItem(
                        icon = Icons.Default.Android,
                        title = "APK ZIPFILE DISSECTION & SHANNON ENTROPY",
                        replacesLabel = "Sherlock / Drozer Mobile OSINT",
                        advancementBadge = "H(X) ≥ 7.85 ENCRYPTED PAYLOAD",
                        summaryText = apkAndHookAuditSummary,
                        detailSpecs = "Direct APK ZipFile Streaming · Byte-Frequency Shannon Entropy · lib/arm64-v8a/*.so Packer Detection · Unprotected IPC Auditor",
                        accentColor = Color(0xFF2DD4BF),
                        onClick = {
                            onInspect?.invoke(
                                "APK ZIPFILE DISSECTION & SHANNON ENTROPY",
                                "On-Device APK Surface, Packer & Security Permission Audit (Replacing Drozer)",
                                apkAuditDetailText.ifBlank { apkAndHookAuditSummary }
                            )
                        }
                    )

                    // Tool 8: Hardware PMIC Coulomb Drain & SHA-256 Merkle Ledger
                    KaliToolItem(
                        icon = Icons.Default.BatteryChargingFull,
                        title = "PMIC COULOMB DRAIN & SHA-256 MERKLE LEDGER",
                        replacesLabel = "Battery Heuristics / Rogue USB-C BadUSB",
                        advancementBadge = "COULOMB P = V × I & MERKLE CHAIN",
                        summaryText = faradayMerkleSummary,
                        detailSpecs = "Hardware Fuel Gauge BATTERY_PROPERTY_CURRENT_NOW × EXTRA_VOLTAGE · Rolling SHA-256 Block Ledger · verifyChainIntegrity() · Faraday & Emulator Detection",
                        accentColor = Color(0xFFF59E0B),
                        onClick = {
                            onInspect?.invoke(
                                "PMIC COULOMB DRAIN & SHA-256 MERKLE LEDGER",
                                "Thermodynamic Electron Accounting & Cryptographic Block Ledger (Replacing Battery Heuristics)",
                                faradayMerkleDetailText.ifBlank { faradayMerkleSummary }
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * Convenience overload accepting [DeviceDetailUiState].
 */
@Composable
fun KaliIntTacticalCard(
    uiState: DeviceDetailUiState,
    onInspect: ((title: String, subtitle: String, fullText: String) -> Unit)? = null,
    onTriggerBleSpam: ((durationSec: Int) -> Unit)? = null,
    onStopBleSpam: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) = KaliIntTacticalCard(
    kismet80211Summary = uiState.kismet80211Summary,
    stingray3gppSummary = uiState.stingray3gppSummary,
    bleTrackerSummary = uiState.bleTrackerSummary,
    tlsDpiProbeSummary = uiState.tlsDpiProbeSummary,
    siliconJniSummary = uiState.siliconJniSummary,
    deepRuntimeIntegritySummary = uiState.deepRuntimeIntegritySummary,
    apkAndHookAuditSummary = uiState.apkAndHookAuditSummary,
    faradayMerkleSummary = uiState.faradayMerkleSummary,
    wifiRadarDetailText = uiState.wifiRadarDetailText,
    stingray3gppDetailText = uiState.stingray3gppDetailText,
    bleRadarDetailText = uiState.bleRadarDetailText,
    socketPeersDetailText = uiState.socketPeersDetailText,
    siliconJniDetailText = uiState.siliconJniDetailText,
    deepRuntimeDetailText = uiState.deepRuntimeDetailText,
    apkAuditDetailText = uiState.apkAuditDetailText,
    faradayMerkleDetailText = uiState.faradayMerkleDetailText,
    defconLevel = uiState.defconLevel,
    onInspect = onInspect,
    onTriggerBleSpam = onTriggerBleSpam,
    onStopBleSpam = onStopBleSpam,
    isBleSpamming = uiState.isBleSpamming,
    bleSpamRemainingSec = uiState.bleSpamRemainingSec,
    modifier = modifier
)

/**
 * Individual Tactical Tool item representing a modernized KaliInt tool.
 */
@Composable
private fun KaliToolItem(
    icon: ImageVector,
    title: String,
    replacesLabel: String,
    advancementBadge: String,
    summaryText: String,
    detailSpecs: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAlert = summaryText.contains("🚨") || summaryText.contains("VIOLATION") || summaryText.contains("Rogue")
    val isWarning = !isAlert && (summaryText.contains("⚠️") || summaryText.contains("Suspicious"))

    val borderColor = when {
        isAlert -> Color(0xFFF43F5E).copy(alpha = 0.6f)
        isWarning -> Color(0xFFFB923C).copy(alpha = 0.6f)
        else -> Color(0xFF1E293B)
    }

    val statusDotColor = when {
        isAlert -> Color(0xFFF43F5E)
        isWarning -> Color(0xFFFB923C)
        else -> accentColor
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F1722),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Title & Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Line 1: Full-width title
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F5F9),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    // Line 2: Replaces label on left, advancementBadge on right
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Replaces: ",
                                fontSize = 9.sp,
                                color = Color(0xFF64748B),
                                softWrap = false
                            )
                            Text(
                                text = replacesLabel,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFF87171),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = accentColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = advancementBadge,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = accentColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live Telemetry Summary
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF090D14),
                border = BorderStroke(0.5.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = summaryText.ifBlank { "Awaiting telemetry stream..." },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isAlert) Color(0xFFFCA5A5) else if (isWarning) Color(0xFFFDE047) else Color(0xFFE2E8F0),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Specs & Inspection Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = detailSpecs,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF64748B),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onClick)
                ) {
                    Text(
                        text = "FORENSICS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}
