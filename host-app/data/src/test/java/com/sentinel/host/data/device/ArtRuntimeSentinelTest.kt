package com.sentinel.host.data.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtRuntimeSentinelTest {

    @Test
    fun testAuditRuntimeIntegrityRunsGracefully() {
        val sentinel = ArtRuntimeSentinel()
        val report = sentinel.auditRuntimeIntegrity()

        assertNotNull(report)
        assertNotNull(report.summary)
        assertNotNull(report.detailReport)
        assertTrue(report.summary.isNotBlank())
        assertTrue(report.detailReport.contains("=== ART VM METHOD MODIFIER & BINDER IPC SENTINEL ==="))
    }

    @Test
    fun testStockRuntimeMethodModifiersAreBytecode() {
        val sentinel = ArtRuntimeSentinel()
        val report = sentinel.auditRuntimeIntegrity()

        // In standard JVM/Robolectric test environment, standard methods are not hooked
        assertFalse("Stock test environment should not have native hook trampolines", report.isHookDetected)
        assertTrue(report.hookedMethods.isEmpty())
    }
}
