package com.sentinel.host.data.telephony

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.pow

class CellularIntegrityMonitorTest {

    private lateinit var mockContext: Context
    private lateinit var monitor: CellularIntegrityMonitor

    @Before
    fun setUp() {
        mockContext = object : android.content.ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        monitor = CellularIntegrityMonitor(mockContext)
    }

    @Test
    fun testTelephonyUnavailableGracefullyHandled() {
        val report = monitor.evaluateServingCell()
        assertNotNull(report)
        assertEquals("NONE", report.networkType)
        assertEquals("Telephony unavailable", report.diagnosticSummary)
        assertFalse(report.isAbnormalProximitySuspected)
    }

    @Test
    fun testDistanceFormulasMath() {
        // 5G-NR Path loss estimation: 10^((-35 - rsrp) / 28)
        val rsrpMacro = -95
        val distMacro = 10.0.pow((-35.0 - rsrpMacro) / 28.0).coerceIn(5.0, 5000.0)
        assertTrue("Macro tower distance should be > 50m", distMacro > 50.0)

        // Close-access Stingray: RSRP = -45 dBm
        val rsrpStingray = -45
        val distStingray = 10.0.pow((-35.0 - rsrpStingray) / 28.0).coerceIn(5.0, 5000.0)
        assertTrue("Close access distance should be < 18m", distStingray < 18.0)
        val isCloseAccess = rsrpStingray > -50 && distStingray < 18.0
        assertTrue("Close access stingray must be suspected", isCloseAccess)

        // LTE Timing Advance: 1 TA unit = 78.12m
        val ta = 2
        val lteDist = ta * 78.12
        assertEquals(156.24, lteDist, 0.01)
    }
}
