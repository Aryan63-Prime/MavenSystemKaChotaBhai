package com.sentinel.host.data.remote.protocol

import com.sentinel.shared.protocol.MessageType
import com.sentinel.shared.protocol.ProtocolVersion
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageSerializerTest {

    private lateinit var serializer: MessageSerializer
    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        moshi = Moshi.Builder().build()
        serializer = MessageSerializer(moshi)
    }

    // ============================================================
    // Outgoing serialization
    // ============================================================

    @Test
    fun `serialize AUTH contains correct type`() {
        val json = serializer.serializeAuth("jwt-token-123", sequence = 1)
        assertTrue(json.contains("\"type\":\"AUTH\""))
    }

    @Test
    fun `serialize AUTH contains token`() {
        val json = serializer.serializeAuth("jwt-token-123", sequence = 1)
        assertTrue(json.contains("\"token\":\"jwt-token-123\""))
    }

    @Test
    fun `serialize AUTH contains version`() {
        val json = serializer.serializeAuth("token", sequence = 1)
        assertTrue(json.contains("\"version\":${ProtocolVersion.CURRENT}"))
    }

    @Test
    fun `serialize AUTH contains sequence`() {
        val json = serializer.serializeAuth("token", sequence = 42)
        assertTrue(json.contains("\"sequence\":42"))
    }

    @Test
    fun `serialize AUTH contains timestamp`() {
        val json = serializer.serializeAuth("token", sequence = 1)
        assertTrue(json.contains("\"timestamp\":"))
    }

    @Test
    fun `serialize REGISTER contains all device fields`() {
        val json = serializer.serializeRegister(
            deviceId = "HOST-0001",
            deviceName = "Pixel 9",
            appVersion = "1.0.0",
            model = "Google Pixel",
            sequence = 2
        )
        assertTrue(json.contains("\"type\":\"REGISTER\""))
        assertTrue(json.contains("\"deviceId\":\"HOST-0001\""))
        assertTrue(json.contains("\"deviceName\":\"Pixel 9\""))
        assertTrue(json.contains("\"appVersion\":\"1.0.0\""))
        assertTrue(json.contains("\"model\":\"Google Pixel\""))
        assertTrue(json.contains("\"sequence\":2"))
    }

    @Test
    fun `serialize HEARTBEAT contains empty data`() {
        val json = serializer.serializeHeartbeat(sequence = 3)
        assertTrue(json.contains("\"type\":\"HEARTBEAT\""))
        assertTrue(json.contains("\"data\":{}"))
        assertTrue(json.contains("\"sequence\":3"))
    }

    @Test
    fun `serialize LOCATION contains all fields`() {
        val json = serializer.serializeLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 5.4f,
            battery = 81,
            network = "5G",
            sequence = 4
        )
        assertTrue(json.contains("\"type\":\"LOCATION\""))
        assertTrue(json.contains("\"latitude\":28.6139"))
        assertTrue(json.contains("\"longitude\":77.209"))
        assertTrue(json.contains("\"battery\":81"))
        assertTrue(json.contains("\"network\":\"5G\""))
    }

    @Test
    fun `serialized AUTH is valid JSON`() {
        val json = serializer.serializeAuth("token", sequence = 1)
        // Verify it can be parsed back as an envelope
        val adapter = moshi.adapter(EnvelopeJson::class.java)
        val envelope = adapter.fromJson(json)!!
        assertEquals("AUTH", envelope.type)
        assertEquals(1L, envelope.sequence)
        assertEquals(ProtocolVersion.CURRENT, envelope.version)
    }

    // ============================================================
    // Incoming deserialization
    // ============================================================

    @Test
    fun `deserialize AUTH_ACK success`() {
        val json = """{"type":"AUTH_ACK","version":1,"timestamp":0,"sequence":1,"data":{"success":true}}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.AuthAck)
        assertTrue((msg as IncomingMessage.AuthAck).success)
        assertEquals(1L, msg.sequence)
        assertEquals(MessageType.AUTH_ACK, msg.type)
    }

    @Test
    fun `deserialize AUTH_ACK failure`() {
        val json = """{"type":"AUTH_ACK","version":1,"timestamp":0,"sequence":1,"data":{"success":false}}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.AuthAck)
        assertFalse((msg as IncomingMessage.AuthAck).success)
    }

    @Test
    fun `deserialize REGISTER_ACK`() {
        val json = """{"type":"REGISTER_ACK","version":1,"timestamp":0,"sequence":2,"data":{"success":true}}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.RegisterAck)
        assertTrue((msg as IncomingMessage.RegisterAck).success)
        assertEquals(2L, msg.sequence)
    }

    @Test
    fun `deserialize HEARTBEAT_ACK`() {
        val json = """{"type":"HEARTBEAT_ACK","version":1,"timestamp":0,"sequence":3}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.HeartbeatAck)
        assertEquals(3L, msg.sequence)
    }

    @Test
    fun `deserialize PONG`() {
        val json = """{"type":"PONG","version":1,"timestamp":0,"sequence":4}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.Pong)
        assertEquals(4L, msg.sequence)
    }

    @Test
    fun `deserialize ERROR`() {
        val json = """{"type":"ERROR","version":1,"timestamp":0,"sequence":5,"data":{"code":401,"message":"Unauthorized"}}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.Error)
        val error = msg as IncomingMessage.Error
        assertEquals(401, error.code)
        assertEquals("Unauthorized", error.message)
        assertEquals(5L, error.sequence)
    }

    @Test
    fun `deserialize unknown type returns Unknown`() {
        val json = """{"type":"FUTURE_TYPE","version":1,"timestamp":0,"sequence":6}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.Unknown)
        assertEquals("FUTURE_TYPE", msg.type)
        assertEquals(6L, msg.sequence)
    }

    @Test
    fun `deserialize empty json returns Unknown`() {
        val json = """{}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.Unknown)
    }

    @Test
    fun `deserialize LISTEN`() {
        val json = """{"type":"LISTEN","version":1,"timestamp":0,"sequence":7,"data":{"deviceId":"HOST-001"}}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.Listen)
        val listen = msg as IncomingMessage.Listen
        assertEquals("HOST-001", listen.deviceId)
        assertEquals(7L, listen.sequence)
    }

    @Test
    fun `deserialize LISTEN with streamId and maxDurationSeconds`() {
        val json = """{"type":"LISTEN","version":1,"timestamp":0,"sequence":7,"data":{"deviceId":"HOST-001","streamId":"550e8400-e29b-41d4-a716-446655440000","maxDurationSeconds":120}}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.Listen)
        val listen = msg as IncomingMessage.Listen
        assertEquals("HOST-001", listen.deviceId)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", listen.streamId)
        assertEquals(120L, listen.maxDurationSeconds)
    }

    @Test
    fun `deserialize STOP`() {
        val json = """{"type":"STOP","version":1,"timestamp":0,"sequence":8,"data":{"deviceId":"HOST-001"}}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.Stop)
        val stop = msg as IncomingMessage.Stop
        assertEquals("HOST-001", stop.deviceId)
        assertEquals(8L, stop.sequence)
    }

    @Test
    fun `deserialize STOP with streamId`() {
        val json = """{"type":"STOP","version":1,"timestamp":0,"sequence":8,"data":{"deviceId":"HOST-001","streamId":"550e8400-e29b-41d4-a716-446655440000"}}"""
        val msg = serializer.deserialize(json)
        assertTrue(msg is IncomingMessage.Stop)
        val stop = msg as IncomingMessage.Stop
        assertEquals("HOST-001", stop.deviceId)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", stop.streamId)
        assertEquals(8L, stop.sequence)
    }

    // ============================================================
    // Round-trip verification
    // ============================================================

    @Test
    fun `serialize then deserialize AUTH produces valid envelope`() {
        val json = serializer.serializeAuth("my-token", sequence = 10)
        val adapter = moshi.adapter(EnvelopeJson::class.java)
        val envelope = adapter.fromJson(json)!!
        assertEquals("AUTH", envelope.type)
        assertEquals(10L, envelope.sequence)
    }
}
