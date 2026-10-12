package com.sentinel.admin.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SystemDiagnosticsDialog(
    data: Map<String, Any>,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Hardware Diagnostics & Telemetry",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .verticalScroll(scrollState)
            ) {
                // Hardware Level Telemetry
                val brand = data["hardwareBrand"]?.toString() ?: ""
                val model = data["hardwareModel"]?.toString() ?: data["model"]?.toString() ?: ""
                val hardwareId = data["hardwareId"]?.toString() ?: ""
                val callsign = data["hardwareCallsign"]?.toString() ?: data["callsign"]?.toString() ?: ""
                val soc = data["hardwareSoc"]?.toString() ?: data["hardwareBoard"]?.toString() ?: ""
                val osRelease = data["osRelease"]?.toString() ?: ""
                val osSdk = data["osSdk"]?.toString() ?: ""
                val secPatch = data["securityPatch"]?.toString() ?: ""
                val res = data["screenResolution"]?.toString() ?: ""
                val dpi = data["screenDpi"]?.toString() ?: ""

                Text(
                    text = "HARDWARE PLATFORM",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (callsign.isNotBlank()) {
                    DiagnosticItem(label = "Hardware Unit", value = "$callsign ($brand $model)")
                } else if (brand.isNotBlank() || model.isNotBlank()) {
                    DiagnosticItem(label = "Device Model", value = "$brand $model".trim())
                }

                if (hardwareId.isNotBlank()) {
                    DiagnosticItem(label = "Hardware ID", value = hardwareId, isMonospace = true)
                }

                if (soc.isNotBlank()) {
                    DiagnosticItem(label = "SoC / Platform", value = soc)
                }

                if (osRelease.isNotBlank()) {
                    DiagnosticItem(label = "Android Version", value = "Android $osRelease (API $osSdk)")
                }

                if (secPatch.isNotBlank()) {
                    DiagnosticItem(label = "Security Patch", value = secPatch)
                }

                if (res.isNotBlank()) {
                    DiagnosticItem(label = "Display Resolution", value = "$res @ ${dpi}dpi")
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "LIVE SYSTEM TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))

                val cpuUsage = data["cpuUsage"]?.toString() ?: "15%"
                val cpuCores = data["cpuCores"]?.toString() ?: ""
                val ramAvailable = data["ramAvailableMb"]?.toString() ?: "0"
                val ramTotal = data["ramTotalMb"]?.toString() ?: "100"
                val storageAvail = data["storageAvailableGb"]?.toString() ?: "N/A"
                val batteryPct = data["batteryPercent"]?.toString() ?: "0"
                val batteryTemp = data["batteryTempC"]?.toString() ?: "0.0"
                val batteryVolt = data["batteryVoltageMv"]?.toString() ?: ""
                val batteryHealth = data["batteryHealth"]?.toString() ?: "Good"
                val batteryTech = data["batteryTech"]?.toString() ?: "Li-poly"
                val wifiSsid = data["wifiSsid"]?.toString() ?: "Unknown"
                val wifiBand = data["wifiBand"]?.toString() ?: ""
                val wifiSpeed = data["wifiLinkSpeed"]?.toString() ?: ""
                val localIp = data["localIp"]?.toString() ?: ""
                val carrier = data["carrier"]?.toString() ?: ""
                val thermalStatus = data["thermalStatus"]?.toString() ?: "Nominal"
                val kernelVer = data["kernelVersion"]?.toString() ?: ""
                val uptime = data["systemUptime"]?.toString() ?: ""
                val isCharging = data["isCharging"] == true

                DiagnosticItem(label = "CPU Load & Cores", value = if (cpuCores.isNotBlank()) "$cpuUsage ($cpuCores Cores)" else cpuUsage)
                DiagnosticItem(label = "RAM Available", value = "$ramAvailable MB / $ramTotal MB")
                DiagnosticItem(label = "Internal Storage Available", value = storageAvail)
                DiagnosticItem(label = "Battery", value = "$batteryPct% (${if (isCharging) "Charging" else "Discharging"}) @ $batteryTemp°C")
                if (batteryVolt.isNotBlank() && batteryVolt != "-1") {
                    DiagnosticItem(label = "Battery Health & Voltage", value = "$batteryHealth · ${batteryVolt}mV ($batteryTech)")
                }
                DiagnosticItem(label = "Thermal Status", value = thermalStatus)

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "NETWORK & RADIO TOPOLOGY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))

                val gatewayIp = data["gatewayIp"]?.toString() ?: ""
                val wifiBssid = data["wifiBssid"]?.toString() ?: ""
                val dnsServers = data["dnsServers"]?.toString() ?: ""
                val netInterface = data["networkInterface"]?.toString() ?: ""
                val powerWatts = data["batteryPowerWatts"]?.toString() ?: ""

                if (carrier.isNotBlank()) {
                    DiagnosticItem(label = "Cellular Network / SIM", value = carrier)
                }
                DiagnosticItem(
                    label = "Wi-Fi Uplink",
                    value = if (wifiBand.isNotBlank() && wifiSpeed.isNotBlank() && wifiSpeed != "N/A") "$wifiSsid · $wifiBand ($wifiSpeed)" else wifiSsid
                )
                if (wifiBssid.isNotBlank() && wifiBssid != "N/A") {
                    DiagnosticItem(label = "Wi-Fi BSSID", value = wifiBssid, isMonospace = true)
                }
                if (localIp.isNotBlank() && localIp != "N/A") {
                    DiagnosticItem(label = "Local IPv4", value = localIp, isMonospace = true)
                }
                if (gatewayIp.isNotBlank() && gatewayIp != "N/A") {
                    DiagnosticItem(label = "Default Gateway", value = gatewayIp, isMonospace = true)
                }
                if (dnsServers.isNotBlank() && dnsServers != "N/A") {
                    DiagnosticItem(label = "DNS Servers", value = dnsServers, isMonospace = true)
                }
                if (netInterface.isNotBlank()) {
                    DiagnosticItem(label = "Network Interface", value = netInterface, isMonospace = true)
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "KERNEL, ROOT & SECURITY POSTURE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))

                val unameSys = data["unameSysname"]?.toString() ?: ""
                val unameRel = data["unameRelease"]?.toString() ?: kernelVer
                val unameArch = data["unameMachine"]?.toString() ?: ""
                val isSelinuxEnforced = data["isSelinuxEnforced"] != false
                val isRooted = data["isRooted"] == true
                val isAdb = data["isAdbEnabled"] == true
                val isDevOpt = data["isDeveloperOptionsEnabled"] == true
                val isUsbConn = data["isUsbConnected"] == true
                val isUsbAdb = data["isUsbAdbActive"] == true
                val isWirelessAdb = data["isWirelessAdbEnabled"] == true
                val isMock = data["isMockLocationEnabled"] == true
                val bootloader = data["bootloader"]?.toString() ?: ""

                if (unameRel.isNotBlank()) {
                    DiagnosticItem(label = "Kernel Release", value = if (unameArch.isNotBlank()) "$unameRel ($unameArch)" else unameRel, isMonospace = true)
                }
                DiagnosticItem(label = "SELinux Status", value = if (isSelinuxEnforced) "Enforcing" else "Permissive")
                DiagnosticItem(
                    label = "Root Status",
                    value = if (isRooted) "COMPROMISED (Root detected)" else "Clean (No root/tamper)"
                )
                DiagnosticItem(label = "ADB Status", value = if (isAdb) "Enabled" else "Disabled")
                DiagnosticItem(
                    label = "USB State",
                    value = when {
                        isUsbConn && isUsbAdb -> "Connected (ADB Link Active)"
                        isUsbConn -> "Connected (Charging/MTP)"
                        else -> "Disconnected"
                    }
                )
                if (isWirelessAdb) {
                    DiagnosticItem(label = "Wireless Debugging", value = "Active (Port open)")
                }
                DiagnosticItem(label = "Developer Options", value = if (isDevOpt) "Enabled" else "Disabled")
                if (isMock) {
                    DiagnosticItem(label = "Mock Locations", value = "Allowed (Warning: Location spoofing possible)")
                }
                if (bootloader.isNotBlank()) {
                    DiagnosticItem(label = "Bootloader", value = bootloader)
                }
                if (uptime.isNotBlank()) {
                    DiagnosticItem(label = "System Uptime", value = uptime)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isRooted) "System Posture: COMPROMISED" else "System Status: Nominal",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRooted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun DiagnosticItem(label: String, value: String, isMonospace: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}
