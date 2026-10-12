package com.sentinel.host.data.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class RootCheckResult(
    val isRooted: Boolean,
    val rootScore: Int,
    val detectedVectors: List<String>
)

/**
 * Enterprise Defense-in-Depth Root & Tamper Detection Manager.
 * Implements 6 distinct auditing vectors:
 * 1. su binary filesystem path probes
 * 2. Custom ROM build tags audit ("test-keys")
 * 3. Superuser and rooting management package enumeration
 * 4. Dangerous build / debug system properties (ro.debuggable, ro.secure)
 * 5. Mount point inspection for writable system partitions and overlayfs
 * 6. Dangerous binaries inspection (busybox, magisk, daemonsu)
 */
@Singleton
class RootDetectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Sentinel:RootDetect"

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/app/Superuser.apk"
        )

        private val KNOWN_ROOT_PACKAGES = listOf(
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "io.github.vvb2060.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingouser.com"
        )

        private val DANGEROUS_BINARIES = listOf(
            "/system/xbin/busybox",
            "/system/bin/busybox",
            "/system/xbin/daemonsu",
            "/system/bin/.ext/.su"
        )
    }

    fun checkRoot(): RootCheckResult {
        val detectedVectors = mutableListOf<String>()

        // Vector 1: su binary detection in PATH & known locations
        for (path in SU_PATHS) {
            try {
                val file = File(path)
                if (file.exists()) {
                    detectedVectors.add("SU binary found: $path")
                }
            } catch (_: Throwable) {}
        }

        // Vector 2: Build tags inspection
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            detectedVectors.add("Test-keys build detected (non-release ROM)")
        }

        // Vector 3: Superuser / Root management packages
        val pm = context.packageManager
        for (pkg in KNOWN_ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                detectedVectors.add("Root manager package detected: $pkg")
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Throwable) {}
        }

        // Vector 4: Dangerous system properties via getprop
        try {
            val debuggable = getSystemProperty("ro.debuggable")
            if (debuggable == "1") {
                detectedVectors.add("System property ro.debuggable=1")
            }
            val secure = getSystemProperty("ro.secure")
            if (secure == "0") {
                detectedVectors.add("System property ro.secure=0")
            }
        } catch (_: Throwable) {}

        // Vector 5: Mount inspection (/proc/mounts)
        try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists() && mountsFile.canRead()) {
                val lines = mountsFile.readLines()
                for (line in lines) {
                    val tokens = line.split("\\s+".toRegex())
                    if (tokens.size >= 4) {
                        val mountPoint = tokens[1]
                        val options = tokens[3]
                        if ((mountPoint == "/system" || mountPoint == "/") && options.contains("rw")) {
                            detectedVectors.add("Writable /system mount detected: $mountPoint")
                        }
                        if (options.contains("overlay") && (mountPoint.contains("magisk") || mountPoint.contains("system"))) {
                            detectedVectors.add("Root overlayfs mount detected: $mountPoint")
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // Vector 6: Dangerous binaries (busybox)
        for (bin in DANGEROUS_BINARIES) {
            try {
                if (File(bin).exists()) {
                    detectedVectors.add("Dangerous binary present: $bin")
                }
            } catch (_: Throwable) {}
        }

        val isRooted = detectedVectors.isNotEmpty()
        if (isRooted) {
            Log.w(TAG, "Root or tampering detected! Vectors: $detectedVectors")
        } else {
            Log.d(TAG, "Root check: Device clean, no tampering detected")
        }

        return RootCheckResult(
            isRooted = isRooted,
            rootScore = detectedVectors.size,
            detectedVectors = detectedVectors
        )
    }

    private fun getSystemProperty(propName: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, propName, "") as String
        } catch (_: Throwable) {
            ""
        }
    }
}
