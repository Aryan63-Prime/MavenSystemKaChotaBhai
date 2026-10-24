package com.sentinel.host.service

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SecureCommandDispatcherTest {

    private val myDeviceId = "DEVICE_HOST_001"
    private val hmacSecret = "TacticalSentinelSecret2026".toByteArray(Charsets.UTF_8)
    private lateinit var dispatcher: SecureCommandDispatcher

    @Before
    fun setUp() {
        dispatcher = SecureCommandDispatcher(myDeviceId, hmacSecret)
        dispatcher.registerHandler("PING") { params ->
            mapOf("ack" to true, "echo" to params.optString("text", ""))
        }
    }

    private fun generateHmac(sequence: Long, timestampSec: Long, targetId: String, command: String): String {
        val canonical = "$sequence|$timestampSec|$targetId|$command"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hmacSecret, "HmacSHA256"))
        }
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testValidCommandDispatchesSuccessfully() = runBlocking {
        val nowSec = System.currentTimeMillis() / 1000L
        val seq = 1001L
        val cmd = "PING"
        val hmac = generateHmac(seq, nowSec, myDeviceId, cmd)

        val packet = JSONObject().apply {
            put("sequence", seq)
            put("timestamp", nowSec)
            put("hmac", hmac)
            put("data", JSONObject().apply {
                put("targetDeviceId", myDeviceId)
                put("command", cmd)
                put("params", JSONObject().put("text", "hello"))
            })
        }

        val result = dispatcher.processIncomingCommand(packet.toString())
        assertTrue("Command must succeed", result.success)
        assertEquals("PING", result.commandId)
        assertEquals(true, result.resultPayload["ack"])
        assertEquals("hello", result.resultPayload["echo"])
    }

    @Test
    fun testTargetDeviceMismatchRejected() = runBlocking {
        val nowSec = System.currentTimeMillis() / 1000L
        val seq = 1002L
        val cmd = "PING"
        val hmac = generateHmac(seq, nowSec, "OTHER_DEVICE", cmd)

        val packet = JSONObject().apply {
            put("sequence", seq)
            put("timestamp", nowSec)
            put("hmac", hmac)
            put("data", JSONObject().apply {
                put("targetDeviceId", "OTHER_DEVICE")
                put("command", cmd)
            })
        }

        val result = dispatcher.processIncomingCommand(packet.toString())
        assertFalse("Device mismatch must be rejected", result.success)
        assertTrue(result.errorMessage?.contains("Target device mismatch") == true)
    }

    @Test
    fun testDuplicateSequenceReplayRejected() = runBlocking {
        val nowSec = System.currentTimeMillis() / 1000L
        val seq = 2001L
        val cmd = "PING"
        val hmac = generateHmac(seq, nowSec, myDeviceId, cmd)

        val packet = JSONObject().apply {
            put("sequence", seq)
            put("timestamp", nowSec)
            put("hmac", hmac)
            put("data", JSONObject().apply {
                put("targetDeviceId", myDeviceId)
                put("command", cmd)
            })
        }

        // First attempt succeeds
        val res1 = dispatcher.processIncomingCommand(packet.toString())
        assertTrue(res1.success)

        // Second attempt with same sequence nonce must be rejected
        val res2 = dispatcher.processIncomingCommand(packet.toString())
        assertFalse("Replayed sequence nonce must be rejected", res2.success)
        assertTrue(res2.errorMessage?.contains("Duplicate sequence nonce") == true)
    }

    @Test
    fun testExpiredTimestampRejected() = runBlocking {
        val expiredSec = (System.currentTimeMillis() / 1000L) - 400L // 400s ago (> 300s window)
        val seq = 3001L
        val cmd = "PING"
        val hmac = generateHmac(seq, expiredSec, myDeviceId, cmd)

        val packet = JSONObject().apply {
            put("sequence", seq)
            put("timestamp", expiredSec)
            put("hmac", hmac)
            put("data", JSONObject().apply {
                put("targetDeviceId", myDeviceId)
                put("command", cmd)
            })
        }

        val result = dispatcher.processIncomingCommand(packet.toString())
        assertFalse("Expired timestamp must be rejected", result.success)
        assertTrue(result.errorMessage?.contains("replay window") == true)
    }

    @Test
    fun testInvalidHmacRejected() = runBlocking {
        val nowSec = System.currentTimeMillis() / 1000L
        val seq = 4001L
        val cmd = "PING"
        val fakeHmac = "deadbeefcafebabe0123456789abcdef0123456789abcdef0123456789abcdef"

        val packet = JSONObject().apply {
            put("sequence", seq)
            put("timestamp", nowSec)
            put("hmac", fakeHmac)
            put("data", JSONObject().apply {
                put("targetDeviceId", myDeviceId)
                put("command", cmd)
            })
        }

        val result = dispatcher.processIncomingCommand(packet.toString())
        assertFalse("Invalid HMAC must be rejected", result.success)
        assertTrue(result.errorMessage?.contains("Invalid HMAC-SHA256") == true)
    }
}
