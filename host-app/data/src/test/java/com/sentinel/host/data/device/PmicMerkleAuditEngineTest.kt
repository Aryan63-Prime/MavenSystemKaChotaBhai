package com.sentinel.host.data.device

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PmicMerkleAuditEngineTest {

    private lateinit var mockContext: Context
    private lateinit var engine: PmicMerkleAuditEngine

    @Before
    fun setUp() {
        mockContext = object : android.content.ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
            override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?): android.content.Intent? = null
            override fun getApplicationContext(): Context = this
        }
        engine = PmicMerkleAuditEngine(mockContext)
    }

    @Test
    fun testPmicSamplingAndMerkleChainCreation() {
        val block0 = engine.samplePmicThermodynamics()
        assertEquals(0L, block0.index)
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", block0.previousHash)
        assertNotNull(block0.eventHash)
        assertEquals(64, block0.eventHash.length) // SHA-256 hex length

        val block1 = engine.samplePmicThermodynamics()
        assertEquals(1L, block1.index)
        assertEquals(block0.eventHash, block1.previousHash)

        val block2 = engine.samplePmicThermodynamics()
        assertEquals(2L, block2.index)
        assertEquals(block1.eventHash, block2.previousHash)

        assertTrue("Chain integrity must be valid", engine.verifyChainIntegrity())
    }

    @Test
    fun testChainIntegrityVerification() {
        for (i in 0 until 5) {
            engine.samplePmicThermodynamics()
        }
        assertTrue("Intact chain must verify successfully", engine.verifyChainIntegrity())
    }
}
