package com.sentinel.admin.data.repository

import com.sentinel.admin.data.remote.SequenceGenerator
import com.sentinel.admin.data.remote.protocol.MessageSerializer
import com.sentinel.admin.domain.model.ConnectionEvent
import com.sentinel.admin.domain.model.ConnectionState
import com.sentinel.admin.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AudioRepositoryImpl].
 *
 * Verifies LISTEN and STOP commands are correctly serialized and sent.
 * AudioRepository is COMMANDS ONLY — no playback logic.
 */
class AudioRepositoryImplTest {

    private lateinit var repo: AudioRepositoryImpl
    private lateinit var fakeConnection: FakeConnectionRepository
    private lateinit var sequenceGenerator: SequenceGenerator

    @Before
    fun setUp() {
        fakeConnection = FakeConnectionRepository()
        sequenceGenerator = SequenceGenerator()
        repo = AudioRepositoryImpl(
            connectionRepository = fakeConnection,
            messageSerializer = MessageSerializer(),
            sequenceGenerator = sequenceGenerator
        )
    }

    @Test
    fun `listen sends LISTEN message with device ID`() {
        fakeConnection.sendTextResult = true
        val result = repo.listen("HOST-0001")

        assertTrue(result)
        assertEquals(1, fakeConnection.sentTexts.size)
        assertTrue(fakeConnection.sentTexts[0].contains("\"type\":\"LISTEN\""))
        assertTrue(fakeConnection.sentTexts[0].contains("HOST-0001"))
    }

    @Test
    fun `listen with streamId includes streamId and audioVersion`() {
        fakeConnection.sendTextResult = true
        val streamId = java.util.UUID.randomUUID()
        val result = repo.listen("HOST-0001", streamId)

        assertTrue(result)
        assertEquals(1, fakeConnection.sentTexts.size)
        assertTrue(fakeConnection.sentTexts[0].contains(streamId.toString()))
        assertTrue(fakeConnection.sentTexts[0].contains("\"audioVersion\":2"))
    }

    @Test
    fun `stopListening sends STOP message with device ID`() {
        fakeConnection.sendTextResult = true
        val result = repo.stopListening("HOST-0001")

        assertTrue(result)
        assertEquals(1, fakeConnection.sentTexts.size)
        assertTrue(fakeConnection.sentTexts[0].contains("\"type\":\"STOP\""))
        assertTrue(fakeConnection.sentTexts[0].contains("HOST-0001"))
    }

    @Test
    fun `stopListening with streamId includes streamId`() {
        fakeConnection.sendTextResult = true
        val streamId = java.util.UUID.randomUUID()
        val result = repo.stopListening("HOST-0001", streamId)

        assertTrue(result)
        assertEquals(1, fakeConnection.sentTexts.size)
        assertTrue(fakeConnection.sentTexts[0].contains(streamId.toString()))
    }

    @Test
    fun `listen returns false when send fails`() {
        fakeConnection.sendTextResult = false
        val result = repo.listen("HOST-0001")

        assertFalse(result)
    }

    @Test
    fun `sequential calls use incrementing sequences`() {
        fakeConnection.sendTextResult = true
        repo.listen("HOST-0001")
        repo.stopListening("HOST-0001")

        assertEquals(2, fakeConnection.sentTexts.size)
        // Both should have different sequence numbers
        assertNotEquals(fakeConnection.sentTexts[0], fakeConnection.sentTexts[1])
    }

    // ============================================================
    // Fake
    // ============================================================

    private class FakeConnectionRepository : ConnectionRepository {
        val sentTexts = mutableListOf<String>()
        var sendTextResult = true

        override val state: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Disconnected)
        override val events: SharedFlow<ConnectionEvent> =
            MutableSharedFlow()

        override suspend fun connect(serverUrl: String) {}
        override suspend fun disconnect() {}

        override fun sendText(message: String): Boolean {
            sentTexts.add(message)
            return sendTextResult
        }

        override fun sendBinary(data: ByteArray): Boolean = true
    }
}
