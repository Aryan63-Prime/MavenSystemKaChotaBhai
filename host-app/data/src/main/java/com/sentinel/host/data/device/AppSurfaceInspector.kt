package com.sentinel.host.data.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.ln

data class AppAttackSurfaceReport(
    val packageName: String,
    val isHeadlessGhost: Boolean,
    val exportedActivitiesCount: Int,
    val unprotectedServicesCount: Int,
    val unprotectedReceiversCount: Int,
    val unprotectedProvidersCount: Int,
    val usesCleartextTraffic: Boolean,
    val nativeLibraries: List<String>,
    val detectedPacker: String?,
    val maxPayloadEntropy: Double,
    val isPackedOrEncrypted: Boolean,
    val dexClassCount: Int = 0,
    val dynamicDropperDetected: Boolean = false,
    val webhookC2Detected: Boolean = false
)

class AppSurfaceInspector(private val context: Context) {

    companion object {
        init {
            try {
                System.loadLibrary("opus_jni")
            } catch (_: Throwable) {}
        }

        @JvmStatic
        private external fun nativeInspectDexHeader(dexBytes: ByteArray): IntArray?

        private val KNOWN_PACKER_LIBS = mapOf(
            "libjiagu" to "Qihoo 360 Jiagu",
            "libsecexe" to "Bangcle / SecNeo",
            "libsecmain" to "Bangcle / SecNeo",
            "libshell" to "Tencent Legu",
            "libbaiduprotect" to "Baidu Protector",
            "libdexprotector" to "DexProtector"
        )

        // Exclude pre-compressed media, fonts, animations, profiles, and localized text assets that naturally have H(X) ~ 7.9-8.0
        private val EXCLUDED_ASSET_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "svg",
            "mp3", "ogg", "wav", "mp4", "m4a", "flac", "aac", "mid", "midi",
            "ttf", "otf", "woff", "woff2", "pfb", "bcmap",
            "lottie", "json", "xml", "html", "htm", "css", "txt", "tsv", "csv", "properties", "spo",
            "zip", "gz", "tgz", "tar", "bz2", "7z", "rar", "br", "arsc",
            "prof", "profm",
            "js", "mjs", "cjs", "map", "jsa"
        )
    }

    fun auditPackage(targetPkg: String): AppAttackSurfaceReport? {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS

        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(targetPkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(targetPkg, flags)
            }
        } catch (_: Exception) {
            return null
        }

        val appInfo = pkgInfo.applicationInfo ?: return null

        // 1. Headless "Ghost APK" Check: No launcher icon YET possesses background Services or Receivers
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(targetPkg)
        }
        val hasLauncherIcon = pm.queryIntentActivities(launcherIntent, 0).isNotEmpty()
        val hasBackgroundDaemons = !pkgInfo.services.isNullOrEmpty() || !pkgInfo.receivers.isNullOrEmpty()
        val isHeadlessGhost = !hasLauncherIcon && hasBackgroundDaemons

        // 2. Drozer-Style Unprotected Exported IPC Surface Counting
        val expAct = pkgInfo.activities?.count { it.exported } ?: 0
        val unprotSvc = pkgInfo.services?.count { it.exported && it.permission.isNullOrBlank() } ?: 0
        val unprotRec = pkgInfo.receivers?.count { it.exported && it.permission.isNullOrBlank() } ?: 0
        val unprotPrv = pkgInfo.providers?.count {
            it.exported && it.readPermission.isNullOrBlank() && it.writePermission.isNullOrBlank()
        } ?: 0

        // 3. Cleartext HTTP Traffic Flag (OWASP MASVS-NETWORK)
        val cleartext = (appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0

        // 4. Direct APK ZipFile Dissection (APKiD Packer Detection + Dual Shannon Entropy & Chi-Square Uniformity)
        val nativeLibs = mutableListOf<String>()
        var detectedPacker: String? = null
        var maxEntropy = 0.0
        var hasUniformEncryptedPayload = false
        var totalDexClasses = 0
        var dynamicDropperFound = false
        var webhookC2Found = false

        try {
            val sourceDir = appInfo.sourceDir
            if (sourceDir != null) {
                ZipFile(sourceDir).use { zip ->
                    val entries = zip.entries()
                    var inspectedEntries = 0
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name

                        if (name.startsWith("lib/") && name.endsWith(".so")) {
                            val soName = name.substringAfterLast('/')
                            if (soName !in nativeLibs) nativeLibs.add(soName)
                            for ((sig, packerLabel) in KNOWN_PACKER_LIBS) {
                                if (soName.contains(sig, ignoreCase = true)) {
                                    detectedPacker = packerLabel
                                }
                            }
                        }

                        // Sample entropy of classes*.dex, native .so, and raw binary payloads (up to 20 files, 16KB per file)
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val isDex = name.startsWith("classes") && name.endsWith(".dex")
                        val isNativeLib = name.endsWith(".so")
                        val isExcludedDir = name.contains("/dexopt/") || name.contains("/compressed/") ||
                                name.contains("com/amazonaws/") || name.contains("com/appsflyer/")
                        val isRawBinaryPayload = isDex || isNativeLib || (name.startsWith("assets/") && ext in setOf("so", "dex", "bin", "dat", "enc", "payload"))

                        if (isDex) {
                            try {
                                zip.getInputStream(entry).use { stream ->
                                    val dexBuf = ByteArray(65536)
                                    var readTotal = 0
                                    while (readTotal < dexBuf.size) {
                                        val r = stream.read(dexBuf, readTotal, dexBuf.size - readTotal)
                                        if (r <= 0) break
                                        readTotal += r
                                    }
                                    val inspectRes = try {
                                        val slice = if (readTotal == dexBuf.size) dexBuf else dexBuf.copyOf(readTotal)
                                        nativeInspectDexHeader(slice)
                                    } catch (_: Throwable) { null }

                                    if (inspectRes != null && inspectRes.size >= 3) {
                                        if (inspectRes[0] > 0) totalDexClasses += inspectRes[0]
                                        if (inspectRes[1] == 1) dynamicDropperFound = true
                                        if (inspectRes[2] == 1) webhookC2Found = true
                                    } else if (readTotal >= 112 && dexBuf[0] == 'd'.code.toByte() && dexBuf[1] == 'e'.code.toByte() && dexBuf[2] == 'x'.code.toByte()) {
                                        val classDefs = (dexBuf[96].toInt() and 0xFF) or
                                                ((dexBuf[97].toInt() and 0xFF) shl 8) or
                                                ((dexBuf[98].toInt() and 0xFF) shl 16) or
                                                ((dexBuf[99].toInt() and 0xFF) shl 24)
                                        if (classDefs > 0) totalDexClasses += classDefs
                                        val s = String(dexBuf, 0, readTotal, Charsets.ISO_8859_1)
                                        if (s.contains("InMemoryDexClassLoader") || s.contains("ProcessBuilder") || s.contains("Runtime;->exec")) {
                                            dynamicDropperFound = true
                                        }
                                        if (s.contains("telegram.org") || s.contains("discord.com/api/webhooks") || s.contains(".onion")) {
                                            webhookC2Found = true
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        if (inspectedEntries < 20 && isRawBinaryPayload && !isExcludedDir && (ext !in EXCLUDED_ASSET_EXTENSIONS) && entry.size > 1024) {
                            inspectedEntries++
                            try {
                                zip.getInputStream(entry).use { stream ->
                                    val (h, chi2) = calculateStreamEntropyAndChiSquare(stream, maxBytes = 16384)
                                    if (h > maxEntropy) maxEntropy = h
                                    // True encrypted/packed shellcode has uniform random distribution (chi2 in 180.0..340.0)
                                    if (h >= 7.85 && chi2 in 180.0..340.0) {
                                        hasUniformEncryptedPayload = true
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val isPacked = detectedPacker != null || hasUniformEncryptedPayload

        return AppAttackSurfaceReport(
            packageName = targetPkg,
            isHeadlessGhost = isHeadlessGhost,
            exportedActivitiesCount = expAct,
            unprotectedServicesCount = unprotSvc,
            unprotectedReceiversCount = unprotRec,
            unprotectedProvidersCount = unprotPrv,
            usesCleartextTraffic = cleartext,
            nativeLibraries = nativeLibs,
            detectedPacker = detectedPacker,
            maxPayloadEntropy = maxEntropy,
            isPackedOrEncrypted = isPacked,
            dexClassCount = totalDexClasses,
            dynamicDropperDetected = dynamicDropperFound,
            webhookC2Detected = webhookC2Found
        )
    }

    fun calculateStreamEntropyBounded(stream: InputStream, maxBytes: Int): Double {
        return calculateStreamEntropyAndChiSquare(stream, maxBytes).first
    }

    fun calculateStreamEntropyAndChiSquare(stream: InputStream, maxBytes: Int): Pair<Double, Double> {
        val frequencies = IntArray(256)
        var totalBytes = 0
        val buf = ByteArray(4096)
        while (totalBytes < maxBytes) {
            val toRead = minOf(buf.size, maxBytes - totalBytes)
            val read = stream.read(buf, 0, toRead)
            if (read <= 0) break
            for (i in 0 until read) {
                frequencies[buf[i].toInt() and 0xFF]++
            }
            totalBytes += read
        }
        if (totalBytes == 0) return Pair(0.0, 0.0)

        var entropy = 0.0
        val log2 = ln(2.0)
        for (f in frequencies) {
            if (f > 0) {
                val p = f.toDouble() / totalBytes
                entropy -= p * (ln(p) / log2)
            }
        }

        // Chi-Square goodness-of-fit against uniform byte distribution
        val expected = totalBytes / 256.0
        var chiSquare = 0.0
        for (f in frequencies) {
            val diff = f.toDouble() - expected
            chiSquare += (diff * diff) / expected
        }

        return Pair(entropy, chiSquare)
    }
}
