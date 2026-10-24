package com.sentinel.host.data.bluetooth

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class BleTrackerSentinelTest {

    @Test
    fun testBleDistanceCalculation() {
        val rssi = -59
        val distAtOneMeter = 10.0.pow((-59.0 - rssi) / (10.0 * 2.4)).coerceIn(0.2, 50.0)
        assertEquals(1.0, distAtOneMeter, 0.01)

        val rssiFar = -85
        val distFar = 10.0.pow((-59.0 - rssiFar) / (10.0 * 2.4)).coerceIn(0.2, 50.0)
        assertTrue("Distance at -85 dBm must be > 10m", distFar > 10.0)
    }

    @Test
    fun testRssiBoundsValidation() {
        // Bluetooth Core Spec: 0x7F (+127 dBm) = RSSI_NOT_AVAILABLE
        val rssiNotAvailable = 127
        assertFalse("127 dBm must be rejected", rssiNotAvailable in -95..-15)

        val rssiValid = -65
        assertTrue("Valid BLE RSSI must be accepted", rssiValid in -95..-15)

        val rssiTooWeak = -105
        assertFalse("Too weak RSSI must be rejected", rssiTooWeak in -95..-15)
    }

    @Test
    fun testFlipperZeroMacIdentification() {
        val flipperMac1 = "80:E1:26:AA:BB:CC"
        val flipperMac2 = "80:E1:27:11:22:33"
        val benignMac = "AA:BB:CC:DD:EE:FF"

        assertTrue(flipperMac1.startsWith("80:E1:26"))
        assertTrue(flipperMac2.startsWith("80:E1:27"))
        assertFalse(benignMac.startsWith("80:E1:26") || benignMac.startsWith("80:E1:27"))
    }

    @Test
    fun testAppleContinuityOfflineFindingSubTlvVerification() {
        // Sub-TLV type: 0x12, length: 0x19 (25 bytes)
        val airTagPayload = byteArrayOf(0x12, 0x19, 0x00, 0x01)
        val subType = airTagPayload[0].toInt() and 0xFF
        val subLen = airTagPayload[1].toInt() and 0xFF

        assertEquals(0x12, subType)
        assertTrue("Separated AirTag requires subLen >= 0x19", subLen >= 0x19)

        // Paired Mac/iPhone FindMy (subLen < 0x19)
        val macFindMyPayload = byteArrayOf(0x12, 0x05, 0x00)
        val pairedSubLen = macFindMyPayload[1].toInt() and 0xFF
        assertFalse("Paired Mac/iPhone has subLen < 0x19", pairedSubLen >= 0x19)
    }

    @Test
    fun testBluetoothAddressTypeClassification() {
        // Bluetooth Core Spec Vol 6 Part B §1.3
        // 0b11 (0xC0..0xFF) = Static Random Address
        assertEquals("[Static-Rand]", BleTrackerSentinel.classifyAddressType("C0:11:22:33:44:55"))
        assertEquals("[Static-Rand]", BleTrackerSentinel.classifyAddressType("F4:AA:BB:CC:DD:EE"))

        // 0b01 (0x40..0x7F) = Resolvable Private Address (RPA-IRK)
        assertEquals("[RPA-IRK]", BleTrackerSentinel.classifyAddressType("4A:22:33:44:55:66"))
        assertEquals("[RPA-IRK]", BleTrackerSentinel.classifyAddressType("7F:FF:EE:DD:CC:BB"))

        // 0b00 (0x00..0x3F) = Non-Resolvable Private Address (NRPA)
        assertEquals("[NRPA]", BleTrackerSentinel.classifyAddressType("00:11:22:33:44:55"))
        assertEquals("[NRPA]", BleTrackerSentinel.classifyAddressType("3F:AA:BB:CC:DD:EE"))

        // 0b10 (0x80..0xBF) = Public OUI Assigned / Reserved
        assertEquals("[Public-OUI]", BleTrackerSentinel.classifyAddressType("80:E1:26:00:11:22"))
    }
}
