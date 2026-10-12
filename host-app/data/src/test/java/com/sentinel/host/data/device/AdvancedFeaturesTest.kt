package com.sentinel.host.data.device

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdvancedFeaturesTest {

    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        mockContext = object : android.content.ContextWrapper(null) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                return PackageManager.PERMISSION_DENIED
            }
            override fun getSystemService(name: String): Any? = null
            override fun getContentResolver(): android.content.ContentResolver? = null
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): PackageManager? = null
        }
    }

    @Test
    fun testRootDetectionManagerExecutesWithoutCrash() {
        val rootManager = RootDetectionManager(mockContext)
        val result = rootManager.checkRoot()
        assertNotNull(result)
        assertNotNull(result.detectedVectors)
        assertTrue(result.rootScore >= 0)
    }

    @Test
    fun testSecuritySettingsMonitorExecutesWithoutCrash() {
        val secMonitor = SecuritySettingsMonitor(mockContext)
        val snapshot = secMonitor.getSnapshot()
        assertNotNull(snapshot)
        assertFalse(snapshot.isAdbEnabled)
        assertFalse(snapshot.isDeveloperOptionsEnabled)
        assertFalse(snapshot.isUsbConnected)
    }

    @Test
    fun testCallLogManagerReportsMissingPermissionWhenDenied() {
        val callLogManager = CallLogManager(mockContext)
        val result = callLogManager.getCallLogs(50, 0)
        assertNotNull(result)
        assertEquals(false, result["permissionGranted"])
        assertEquals(0, result["total"])
        assertTrue((result["callLogs"] as List<*>).isEmpty())
    }

    @Test
    fun testContactsManagerReportsMissingPermissionWhenDenied() {
        val contactsManager = ContactsManager(mockContext)
        val result = contactsManager.getContacts(10, 0)
        assertNotNull(result)
        assertEquals(false, result["permissionGranted"])
        assertEquals(0, result["total"])
        assertTrue((result["contacts"] as List<*>).isEmpty())
    }

    @Test
    fun testPackageInspectorHandlesMissingPackageManagerGracefully() {
        val packageInspector = PackageInspector(mockContext)
        val result = packageInspector.getInstalledPackages(false, 50, 0)
        assertNotNull(result)
        assertEquals(0, result["total"])
        assertTrue((result["apps"] as List<*>).isEmpty())
    }
}
