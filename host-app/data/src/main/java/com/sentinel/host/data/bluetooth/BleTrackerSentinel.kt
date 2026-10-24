package com.sentinel.host.data.bluetooth

import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import java.util.Locale
import kotlin.math.pow

data class BleSecurityAlert(
    val deviceAddress: String,
    val vendorClassification: String,
    val rssiDbm: Int,
    val estimatedDistanceMeters: Double,
    val isThreat: Boolean,
    val addressType: String = "[Public-OUI]",
    val deltaMs: Long = 0L
)

class BleTrackerSentinel {

    companion object {
        private const val COMPANY_ID_APPLE = 0x004C
        private const val COMPANY_ID_MICROSOFT = 0x0006
        private const val COMPANY_ID_SAMSUNG = 0x0075
        private const val AIRDROP_TYPE = 0x05
        private const val PROXIMITY_PAIRING_TYPE = 0x07
        private const val APPLE_TV_TYPE = 0x0D
        private const val OFFLINE_FINDING_TYPE = 0x12
        private const val SEPARATED_AIRTAG_LEN = 0x19 // 25-byte P-224 public key payload

        /**
         * Bluetooth Core Specification Vol 6 Part B §1.3:
         * Classifies 48-bit Device Address by inspecting top two bits of MSB:
         * - 0b11 (0xC0..0xFF): Static Random Address
         * - 0b01 (0x40..0x7F): Resolvable Private Address (RPA-IRK)
         * - 0b00 (0x00..0x3F): Non-Resolvable Private Address (NRPA)
         * - Fallback: Public IEEE OUI Assigned Address
         */
        fun classifyAddressType(mac: String): String {
            val topByte = mac.take(2).toIntOrNull(16) ?: return "[Public-OUI]"
            return when (topByte and 0xC0) {
                0xC0 -> "[Static-Rand]"
                0x40 -> "[RPA-IRK]"
                0x00 -> "[NRPA]"
                else -> "[Public-OUI]"
            }
        }
    }

    // 6-second sliding window for multi-MAC pairing flood / BLE spam detection
    private val recentPairingBursts = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, String>>()

    // Inter-arrival timestamp tracking for clock-skew (Δt)
    private val lastSeenTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastDeltaMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun parseScanResult(result: ScanResult): BleSecurityAlert? {
        val record = result.scanRecord ?: return null
        val rssi = result.rssi

        // Bluetooth Core Spec Vol 4 Part E §7.7.65.2: 0x7F (+127 dBm) = RSSI_NOT_AVAILABLE
        if (rssi !in -95..-15) return null

        val mac = result.device?.address?.uppercase(Locale.US) ?: return null
        val name = try { record.deviceName ?: "" } catch (_: Throwable) { "" }
        val distM = 10.0.pow((-59.0 - rssi) / (10.0 * 2.4)).coerceIn(0.2, 50.0)

        // Radiometric clock-skew tracking (Δt inter-arrival jitter in ms)
        val now = System.currentTimeMillis()
        val prevSeen = lastSeenTimestamps[mac]
        lastSeenTimestamps[mac] = now
        val deltaMs = if (prevSeen != null) (now - prevSeen) else 0L
        if (deltaMs > 0) lastDeltaMs[mac] = deltaMs
        val addressType = classifyAddressType(mac)

        // 1. Flipper Zero STMicroelectronics WB55 OUI (80:E1:26 / 80:E1:27)
        if (mac.startsWith("80:E1:26") || mac.startsWith("80:E1:27") || name.contains("Flipper", ignoreCase = true)) {
            return BleSecurityAlert(mac, "🚨 Flipper Zero Multi-Tool", rssi, distM, isThreat = true, addressType = addressType, deltaMs = deltaMs)
        }

        // 2. Apple Continuity / FindMy Offline Finding & Proximity Pairing (0x004C)
        val appleBytes = record.getManufacturerSpecificData(COMPANY_ID_APPLE)
        if (appleBytes != null && appleBytes.size >= 2) {
            val subType = appleBytes[0].toInt() and 0xFF
            val subLen = appleBytes[1].toInt() and 0xFF

            if (subType == PROXIMITY_PAIRING_TYPE) {
                // Track multi-MAC pairing flood in 6s window
                recentPairingBursts.add(Pair(now, mac))
                val cutoff = now - 6000L
                while (recentPairingBursts.peek()?.let { it.first < cutoff } == true) {
                    recentPairingBursts.poll()
                }
                val distinctPairingMacs = recentPairingBursts.map { it.second }.distinct().size
                if (distinctPairingMacs >= 6) {
                    return BleSecurityAlert(
                        mac,
                        "🚨 BLE SPAM FLOOD (${distinctPairingMacs} MACs/6s Multi-Identity Flood)",
                        rssi,
                        distM,
                        isThreat = true,
                        addressType = addressType,
                        deltaMs = deltaMs
                    )
                }
            }

            when (subType) {
                OFFLINE_FINDING_TYPE -> {
                    return if (subLen >= SEPARATED_AIRTAG_LEN && rssi >= -72) {
                        BleSecurityAlert(mac, "⚠️ Separated Apple AirTag / FindMy Tracker", rssi, distM, isThreat = true, addressType = addressType, deltaMs = deltaMs)
                    } else {
                        BleSecurityAlert(mac, "Apple FindMy Peer (Paired Mac/iPhone)", rssi, distM, isThreat = false, addressType = addressType, deltaMs = deltaMs)
                    }
                }
                PROXIMITY_PAIRING_TYPE -> {
                    val isFlood = rssi >= -55
                    return BleSecurityAlert(
                        mac,
                        if (isFlood) "⚠️ Apple Continuity Pairing Beacon (AirPods/Beats Flood)" else "Apple Continuity Pairing Peripheral",
                        rssi,
                        distM,
                        isThreat = isFlood,
                        addressType = addressType,
                        deltaMs = deltaMs
                    )
                }
                AIRDROP_TYPE -> {
                    return BleSecurityAlert(mac, "Apple AirDrop Discovery Beacon", rssi, distM, isThreat = false, addressType = addressType, deltaMs = deltaMs)
                }
                APPLE_TV_TYPE -> {
                    return BleSecurityAlert(mac, "Apple TV / HomePod Setup Beacon", rssi, distM, isThreat = false, addressType = addressType, deltaMs = deltaMs)
                }
            }
        }

        // 3. Samsung SmartTag (0xFD5A), Tile (0xFEED) & Google FastPair (0xFE2C)
        val uuids = record.serviceUuids?.map { it.uuid.toString().lowercase(Locale.US) } ?: emptyList()
        if (uuids.any { it.contains("fd5a") }) {
            return BleSecurityAlert(mac, "⚠️ Samsung SmartTag Tracker", rssi, distM, isThreat = rssi >= -70, addressType = addressType, deltaMs = deltaMs)
        }
        if (uuids.any { it.contains("feed") }) {
            return BleSecurityAlert(mac, "⚠️ Tile BLE Tracker", rssi, distM, isThreat = rssi >= -70, addressType = addressType, deltaMs = deltaMs)
        }
        if (uuids.any { it.contains("fe2c") }) {
            // Track multi-MAC pairing flood
            recentPairingBursts.add(Pair(now, mac))
            val cutoff = now - 6000L
            while (recentPairingBursts.peek()?.let { it.first < cutoff } == true) {
                recentPairingBursts.poll()
            }
            val distinctPairingMacs = recentPairingBursts.map { it.second }.distinct().size
            if (distinctPairingMacs >= 6) {
                return BleSecurityAlert(
                    mac,
                    "🚨 BLE SPAM FLOOD (${distinctPairingMacs} FastPair MACs/6s)",
                    rssi,
                    distM,
                    isThreat = true,
                    addressType = addressType,
                    deltaMs = deltaMs
                )
            }
            return BleSecurityAlert(mac, "Google FastPair Peripheral", rssi, distM, isThreat = rssi >= -50, addressType = addressType, deltaMs = deltaMs)
        }

        // 4. Microsoft SwiftPair (Company ID 0x0006)
        val msBytes = record.getManufacturerSpecificData(COMPANY_ID_MICROSOFT)
        if (msBytes != null && msBytes.isNotEmpty() && msBytes[0] == 0x03.toByte()) {
            return BleSecurityAlert(mac, "Microsoft SwiftPair Peripheral", rssi, distM, isThreat = false, addressType = addressType, deltaMs = deltaMs)
        }

        return null
    }

    fun createScanSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setReportDelay(0L)
        .build()
}
