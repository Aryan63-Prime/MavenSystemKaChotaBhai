package com.sentinel.host.data.wifi

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WifiBeaconInspectorTest {

    @Test
    fun testRsnCapabilitiesOffsetCalculationAndLittleEndianParsing() {
        // Construct standard WPA2/WPA3 RSN IE buffer in Little-Endian:
        // Version: 1 (2 bytes: 0x01, 0x00 in Little-Endian)
        // Group Cipher OUI: 4 bytes
        // Pairwise Cipher Count: 1 (2 bytes: 0x01, 0x00 in Little-Endian)
        // Pairwise Cipher Suite: 4 bytes
        // AKM Suite Count: 1 (2 bytes: 0x01, 0x00 in Little-Endian)
        // AKM Suite: 4 bytes
        // RSN Capabilities: 2 bytes (0xC0, 0x00 -> MFPC + MFPR)
        val rsnBytes = byteArrayOf(
            0x01, 0x00,                         // Version = 1
            0x00, 0x0F, 0xAC.toByte(), 0x04,     // Group Cipher (CCMP)
            0x01, 0x00,                         // Pairwise Count = 1
            0x00, 0x0F, 0xAC.toByte(), 0x04,     // Pairwise Suite (CCMP)
            0x01, 0x00,                         // AKM Count = 1
            0x00, 0x0F, 0xAC.toByte(), 0x08,     // AKM Suite (SAE)
            0xC0.toByte(), 0x00                  // RSN Caps (Bit 6: MFPR, Bit 7: MFPC)
        )

        val buffer = ByteBuffer.wrap(rsnBytes).order(ByteOrder.LITTLE_ENDIAN)
        val totalLen = rsnBytes.size
        assertEquals(20, totalLen)

        val offset = WifiBeaconInspector.findRsnCapabilitiesOffset(buffer, totalLen)
        assertEquals(18, offset)

        // Read caps at offset
        buffer.position(offset)
        val caps = buffer.short.toInt() and 0xFFFF
        val pmfCapable = (caps and 0x0080) != 0
        val pmfRequired = (caps and 0x0040) != 0

        assertTrue("MFPC (PMF Capable) must be true", pmfCapable)
        assertTrue("MFPR (PMF Required) must be true", pmfRequired)
    }

    @Test
    fun testFindRsnCapabilitiesOffsetReturnsMinusOneOnTruncatedBuffer() {
        val truncatedBytes = byteArrayOf(0x01, 0x00, 0x00, 0x0F)
        val buffer = ByteBuffer.wrap(truncatedBytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = WifiBeaconInspector.findRsnCapabilitiesOffset(buffer, truncatedBytes.size)
        assertEquals(-1, offset)
    }

    @Test
    fun testWifiSecurityProfileCsaAttackDefaultAndFlag() {
        val cleanProfile = WifiSecurityProfile(
            ssid = "TestSSID",
            bssid = "00:11:22:33:44:55",
            isPmfRequired = true,
            isPmfCapable = true,
            stationCount = 4,
            channelUtilizationPct = 12,
            wpsHardwareModel = null
        )
        assertFalse(cleanProfile.isCsaAttackDetected)

        val attackedProfile = cleanProfile.copy(isCsaAttackDetected = true)
        assertTrue(attackedProfile.isCsaAttackDetected)
    }
}
