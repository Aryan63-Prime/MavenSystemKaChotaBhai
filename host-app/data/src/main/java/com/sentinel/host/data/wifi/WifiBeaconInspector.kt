package com.sentinel.host.data.wifi

import android.net.wifi.ScanResult
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WifiSecurityProfile(
    val ssid: String,
    val bssid: String,
    val isPmfRequired: Boolean,
    val isPmfCapable: Boolean,
    val stationCount: Int?,
    val channelUtilizationPct: Int?,
    val wpsHardwareModel: String?,
    val isCsaAttackDetected: Boolean = false
)

object WifiBeaconInspector {

    private const val IE_QBSS_LOAD = 11      // 0x0B: 802.11e QBSS Load
    private const val IE_CSA = 37            // 0x25: 802.11h Channel Switch Announcement
    private const val IE_RSN = 48            // 0x30: Robust Security Network (WPA2/WPA3)
    private const val IE_VENDOR_SPEC = 221   // 0xDD: Vendor Specific (WPS OUI 00:50:F2:04)

    fun inspectScanResult(scan: ScanResult): WifiSecurityProfile {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return fallbackProfile(scan)
        }

        var pmfRequired = false
        var pmfCapable = false
        var staCount: Int? = null
        var chanUtilPct: Int? = null
        var wpsModel = ""
        var csaDetected = false

        val elements = try {
            scan.informationElements
        } catch (_: Throwable) {
            null
        } ?: return fallbackProfile(scan)

        for (element in elements) {
            val rawBuf = try {
                element.bytes
            } catch (_: Throwable) {
                null
            } ?: continue

            val buffer = rawBuf.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            val totalLen = buffer.remaining()

            when (element.id) {
                IE_CSA -> {
                    // IEEE 802.11h / 802.11y Tag 37: Channel Switch Announcement (CSA)
                    // Used in rogue AP deauth attacks to force STA channel hop
                    if (totalLen >= 3) {
                        csaDetected = true
                    }
                }
                IE_RSN -> {
                    // IEEE 802.11 RSN IE (Little-Endian):
                    // Version (2B = 0x0001) + Group Cipher (4B) + Pairwise Count (2B) +
                    // Pairwise Suites (4B * pwCount) + AKM Count (2B) + AKM Suites (4B * akmCount) + RSN Caps (2B)
                    if (totalLen >= 18) {
                        val version = buffer.short.toInt() and 0xFFFF
                        if (version == 1) {
                            val rsnCapOffset = findRsnCapabilitiesOffset(buffer, totalLen)
                            if (rsnCapOffset != -1 && rsnCapOffset + 2 <= buffer.limit()) {
                                buffer.position(rsnCapOffset)
                                val caps = buffer.short.toInt() and 0xFFFF
                                pmfCapable = (caps and 0x0080) != 0   // Bit 7: MFPC (Capable)
                                pmfRequired = (caps and 0x0040) != 0  // Bit 6: MFPR (Required)
                            }
                        }
                    }
                }
                IE_QBSS_LOAD -> {
                    // 2B Station Count (Little-Endian uint16) + 1B Channel Utilization (0..255)
                    if (totalLen >= 5) {
                        staCount = buffer.short.toInt() and 0xFFFF
                        val rawUtil = buffer.get().toInt() and 0xFF
                        chanUtilPct = (rawUtil * 100) / 255
                    }
                }
                IE_VENDOR_SPEC -> {
                    // Microsoft WPS OUI: 00:50:F2:04 (Big-Endian TLVs inside Vendor IE)
                    if (totalLen >= 8) {
                        val bytes = ByteArray(totalLen)
                        buffer.get(bytes)
                        if (bytes[0] == 0x00.toByte() && bytes[1] == 0x50.toByte() &&
                            bytes[2] == 0xF2.toByte() && bytes[3] == 0x04.toByte()
                        ) {
                            var idx = 4
                            while (idx + 4 <= totalLen) {
                                val tlvType = ((bytes[idx].toInt() and 0xFF) shl 8) or (bytes[idx + 1].toInt() and 0xFF)
                                val tlvLen = ((bytes[idx + 2].toInt() and 0xFF) shl 8) or (bytes[idx + 3].toInt() and 0xFF)
                                idx += 4
                                if (idx + tlvLen <= totalLen && (tlvType == 0x1021 || tlvType == 0x1023)) {
                                    val str = String(bytes, idx, tlvLen, Charsets.UTF_8).trim()
                                    if (str.isNotBlank() && !wpsModel.contains(str)) {
                                        wpsModel = if (wpsModel.isEmpty()) str else "$wpsModel $str"
                                    }
                                }
                                idx += tlvLen
                            }
                        }
                    }
                }
            }
        }

        return WifiSecurityProfile(
            ssid = scan.SSID ?: "<Hidden>",
            bssid = scan.BSSID ?: "00:00:00:00:00:00",
            isPmfRequired = pmfRequired,
            isPmfCapable = pmfCapable,
            stationCount = staCount,
            channelUtilizationPct = chanUtilPct,
            wpsHardwareModel = wpsModel.ifBlank { null },
            isCsaAttackDetected = csaDetected
        )
    }

    fun findRsnCapabilitiesOffset(buffer: ByteBuffer, totalLen: Int): Int {
        return try {
            buffer.position(6) // Skip Version (2B) + Group Cipher (4B)
            val pairwiseCount = buffer.short.toInt() and 0xFFFF
            val akmPos = 8 + (pairwiseCount * 4)
            if (akmPos + 2 > totalLen) return -1
            buffer.position(akmPos)
            val akmCount = buffer.short.toInt() and 0xFFFF
            val capsPos = akmPos + 2 + (akmCount * 4)
            if (capsPos + 2 <= totalLen) capsPos else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun fallbackProfile(scan: ScanResult): WifiSecurityProfile {
        val caps = scan.capabilities ?: ""
        val isWpa3 = caps.contains("SAE") || caps.contains("WPA3")
        return WifiSecurityProfile(
            ssid = scan.SSID ?: "<Hidden>",
            bssid = scan.BSSID ?: "Unknown",
            isPmfRequired = isWpa3,
            isPmfCapable = isWpa3,
            stationCount = null,
            channelUtilizationPct = null,
            wpsHardwareModel = null
        )
    }
}
