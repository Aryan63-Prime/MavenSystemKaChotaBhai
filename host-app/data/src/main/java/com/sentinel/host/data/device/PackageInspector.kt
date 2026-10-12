package com.sentinel.host.data.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enterprise Installed Applications & Package Inventory Inspector.
 * Uses [PackageManager] with QUERY_ALL_PACKAGES permission to audit all installed apps,
 * differentiating system packages from third-party user applications,
 * tracking install/update times, and target SDK levels.
 */
@Singleton
class PackageInspector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Sentinel:PkgInspector"
    }

    fun getInstalledPackages(
        includeSystem: Boolean = false,
        limit: Int = 200,
        offset: Int = 0
    ): Map<String, Any?> {
        val pm = context.packageManager
        val result = mutableMapOf<String, Any?>()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        try {
            val flags = PackageManager.GET_META_DATA
            val allPackages = pm.getInstalledPackages(flags)

            val filtered = allPackages.filter { pkg ->
                val appInfo = pkg.applicationInfo ?: return@filter false
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                includeSystem || (!isSystem && !isUpdatedSystem)
            }.sortedBy { it.applicationInfo?.loadLabel(pm)?.toString()?.lowercase(Locale.ROOT) ?: it.packageName }

            val total = filtered.size
            val targetEnd = if (limit > 0) (offset + limit).coerceAtMost(total) else total
            val paged = if (offset < total) filtered.subList(offset, targetEnd) else emptyList()

            val appList = paged.map { pkg ->
                val appInfo = pkg.applicationInfo
                val appName = appInfo?.loadLabel(pm)?.toString() ?: pkg.packageName
                val isSystem = if (appInfo != null) (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 else false
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkg.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toLong()
                }

                mapOf(
                    "packageName" to pkg.packageName,
                    "appName" to appName,
                    "versionName" to (pkg.versionName ?: ""),
                    "versionCode" to versionCode,
                    "firstInstallTime" to pkg.firstInstallTime,
                    "firstInstallFormatted" to dateFormat.format(Date(pkg.firstInstallTime)),
                    "lastUpdateTime" to pkg.lastUpdateTime,
                    "lastUpdateFormatted" to dateFormat.format(Date(pkg.lastUpdateTime)),
                    "isSystemApp" to isSystem,
                    "targetSdk" to (appInfo?.targetSdkVersion ?: 0),
                    "isEnabled" to (appInfo?.enabled ?: true)
                )
            }

            result["total"] = total
            result["returned"] = appList.size
            result["offset"] = offset
            result["includeSystem"] = includeSystem
            result["apps"] = appList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query installed packages: ${e.message}", e)
            result["error"] = e.message ?: "Failed to query packages"
            result["total"] = 0
            result["apps"] = emptyList<Map<String, Any>>()
        }

        return result
    }
}
