package com.sentinel.host.data.bluetooth

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BleSpamTransmitterTest {

    private lateinit var mockContext: Context
    private lateinit var transmitter: BleSpamTransmitter

    @Before
    fun setUp() {
        mockContext = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        transmitter = BleSpamTransmitter(mockContext, null)
    }

    @Test
    fun testBuildPayloadQueueAllModes() {
        val allQueue = transmitter.getRawPayloads("ALL")
        assertEquals(8, allQueue.size)

        val labels = allQueue.map { it.label }
        assertTrue(labels.any { it.contains("AirPods Pro") })
        assertTrue(labels.any { it.contains("AirPods Max") })
        assertTrue(labels.any { it.contains("AppleTV") })
        assertTrue(labels.any { it.contains("Pixel Buds") })
        assertTrue(labels.any { it.contains("Galaxy Buds") })
        assertTrue(labels.any { it.contains("Surface") })
    }

    @Test
    fun testBuildPayloadQueueAppleOnly() {
        val appleQueue = transmitter.getRawPayloads("APPLE")
        assertEquals(3, appleQueue.size)
        assertTrue(appleQueue.all { it.label.contains("Apple") })
    }

    @Test
    fun testBuildPayloadQueueFastPairOnly() {
        val fpQueue = transmitter.getRawPayloads("FASTPAIR")
        assertEquals(4, fpQueue.size)
        assertTrue(fpQueue.all { it.label.contains("FastPair") })
    }

    @Test
    fun testBuildPayloadQueueSwiftPairOnly() {
        val spQueue = transmitter.getRawPayloads("SWIFTPAIR")
        assertEquals(1, spQueue.size)
        assertTrue(spQueue.first().label.contains("SwiftPair"))
    }

    @Test
    fun testPayloadSizesWithinBle31ByteLimit() {
        assertEquals(21, BleSpamTransmitter.PAYLOAD_APPLE_AIRPODS_PRO.size)
        assertEquals(21, BleSpamTransmitter.PAYLOAD_APPLE_AIRPODS_MAX.size)
        assertEquals(9, BleSpamTransmitter.PAYLOAD_APPLE_TV_SETUP.size)
        assertEquals(10, BleSpamTransmitter.PAYLOAD_MICROSOFT_SWIFTPAIR.size)

        // Verify manufacturer data + headers fit in 31-byte BLE legacy packet limit (24 bytes max for mfg data)
        assertTrue(BleSpamTransmitter.PAYLOAD_APPLE_AIRPODS_PRO.size <= 24)
        assertTrue(BleSpamTransmitter.PAYLOAD_APPLE_AIRPODS_MAX.size <= 24)
        assertTrue(BleSpamTransmitter.PAYLOAD_APPLE_TV_SETUP.size <= 24)
        assertTrue(BleSpamTransmitter.PAYLOAD_MICROSOFT_SWIFTPAIR.size <= 24)

        for (fp in BleSpamTransmitter.FASTPAIR_MODELS) {
            assertEquals(3, fp.size)
        }
    }

    @Test
    fun testStartSpamWithoutBluetoothAdapterReturnsFalse() {
        val result = transmitter.startSpamSequence(30, "ALL")
        assertFalse(result)
        assertFalse(transmitter.isSpamming)
        assertEquals("IDLE", transmitter.currentPayloadLabel)
    }

    @Test
    fun testStopSpamResetsState() {
        transmitter.stopSpam()
        assertFalse(transmitter.isSpamming)
        assertEquals("IDLE", transmitter.currentPayloadLabel)
        assertEquals(0, transmitter.remainingSeconds)
    }
}
