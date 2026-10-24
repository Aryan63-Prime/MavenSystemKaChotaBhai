package com.sentinel.admin.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

data class SovereignSiliconProof(
    val adminId: String,
    val siliconDnaHash: String,
    val isSovereignIqoo13Silicon: Boolean,
    val deviceModel: String,
    val socModel: String,
    val roleClaim: String,
    val hardwareSummary: String,
    val hwNonce: String,
    val hwSignature: String,
    val hwCertBase64: String
)

class SovereignSiliconAttestor(private val context: Context) {

    companion object {
        private const val KEY_ALIAS = "sentinel_sovereign_iqoo13_admin001_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        private val keyLock = Any()
        @Volatile private var cachedDnaHash: String? = null
        @Volatile private var cachedCertBase64: String? = null
    }

    fun isSovereignIqoo13Hardware(): Boolean {
        val model = (Build.MODEL ?: "").trim()
        val brand = (Build.BRAND ?: "").trim()
        val manufacturer = (Build.MANUFACTURER ?: "").trim()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (Build.SOC_MODEL ?: "").trim() else ""
        val arch = try { Os.uname().machine } catch (_: Throwable) { Build.SUPPORTED_ABIS.firstOrNull() ?: "" }

        val isIqoo13 = model.equals("I2401", ignoreCase = true) ||
            (brand.equals("iQOO", ignoreCase = true) && soc.contains("SM8750", ignoreCase = true))
        val isArm64 = arch.contains("aarch64", ignoreCase = true) || arch.contains("arm64", ignoreCase = true)
        val isPhysical = !Build.HARDWARE.contains("ranchu", ignoreCase = true) &&
            !Build.HARDWARE.contains("goldfish", ignoreCase = true) &&
            !manufacturer.contains("Genymotion", ignoreCase = true)

        return isIqoo13 && isArm64 && isPhysical
    }

    fun computeSiliconDnaHash(): String {
        cachedDnaHash?.let { return it }
        return synchronized(keyLock) {
            cachedDnaHash?.let { return it }
            val widevineFuseHex = getTrustZoneWidevineIdHex()
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "no_aid"
            } catch (_: Throwable) { "no_aid" }
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.BOARD
            val arch = try { Os.uname().machine } catch (_: Throwable) { "arm64" }
            val cpuTopology = try {
                File("/sys/devices/system/cpu/present").takeIf { it.exists() }?.readText()?.trim() ?: "0-7"
            } catch (_: Throwable) { "0-7" }
            val apkCertHash = getApkSigningCertSha256()

            val composite = buildString {
                append("SOVEREIGN_ADMIN001_DNA_V1|")
                append("MODEL=").append(Build.MODEL).append("|")
                append("BRAND=").append(Build.BRAND).append("|")
                append("SOC=").append(soc).append("|")
                append("BOARD=").append(Build.BOARD).append("|")
                append("ARCH=").append(arch).append("|")
                append("CPUS=").append(cpuTopology).append("|")
                append("TZ_OTP=").append(widevineFuseHex).append("|")
                append("AID=").append(androidId).append("|")
                append("APK_CERT=").append(apkCertHash)
            }

            val digest = MessageDigest.getInstance("SHA-256").digest(composite.toByteArray(Charsets.UTF_8))
            val hash = digest.joinToString("") { "%02X".format(it) }
            cachedDnaHash = hash
            hash
        }
    }

    private fun getTrustZoneWidevineIdHex(): String {
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE_UUID)
            val uniqueId = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            MessageDigest.getInstance("SHA-256").digest(uniqueId).joinToString("") { "%02X".format(it) }
        } catch (_: Throwable) {
            "TZ_FALLBACK_${Build.BOARD}_${Build.HARDWARE}"
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm?.close() else @Suppress("DEPRECATION") drm?.release()
            } catch (_: Throwable) {}
        }
    }

    private fun getApkSigningCertSha256(): String {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val rawCert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures?.firstOrNull()?.toByteArray()
            } ?: return "UNSIGNED"
            MessageDigest.getInstance("SHA-256").digest(rawCert).joinToString("") { "%02X".format(it) }
        } catch (_: Throwable) {
            "UNKNOWN_CERT"
        }
    }

    private fun ensureHardwareKeyExists(dnaHash: String) {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(KEY_ALIAS)) {
                val buildSpec = { useStrongBox: Boolean ->
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                setAttestationChallenge("SOVEREIGN_ADMIN_001:$dnaHash".toByteArray(Charsets.UTF_8))
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useStrongBox) {
                                setIsStrongBoxBacked(true)
                            }
                        }
                        .build()
                }
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                try {
                    kpg.initialize(buildSpec(true))
                    kpg.generateKeyPair()
                } catch (_: Throwable) {
                    kpg.initialize(buildSpec(false))
                    kpg.generateKeyPair()
                }
            }
        } catch (_: Throwable) {}
    }

    fun generateSiliconProof(adminId: String = "ADMIN-001"): SovereignSiliconProof {
        val dnaHash = computeSiliconDnaHash()
        val isIqoo13 = isSovereignIqoo13Hardware()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        val ts = System.currentTimeMillis() / 1000
        val nonce = "$adminId:${dnaHash.take(32)}:${Build.MODEL}:$soc:$ts"

        val (sigB64, certB64) = synchronized(keyLock) {
            ensureHardwareKeyExists(dnaHash)

            val sig = try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                if (entry != null) {
                    val signer = Signature.getInstance("SHA256withECDSA").apply {
                        initSign(entry.privateKey)
                        update(nonce.toByteArray(Charsets.UTF_8))
                    }
                    Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
                } else ""
            } catch (_: Throwable) { "" }

            val cert = cachedCertBase64 ?: try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val x509 = ks.getCertificateChain(KEY_ALIAS)?.firstOrNull() ?: ks.getCertificate(KEY_ALIAS)
                if (x509 != null) {
                    val b64 = Base64.encodeToString(x509.encoded, Base64.NO_WRAP)
                    cachedCertBase64 = b64
                    b64
                } else ""
            } catch (_: Throwable) { "" }

            Pair(sig, cert)
        }

        val roleClaim = if (isIqoo13 && adminId == "ADMIN-001") "SOVEREIGN_ROOT_ADMIN" else "TEMP_OPERATOR"
        val summary = if (isIqoo13) {
            "SOVEREIGN ROOT ADMIN (ADMIN-001 · Vivo iQOO 13 I2401 · Snapdragon 8 Elite SM8750 · DNA:${dnaHash.take(12)})"
        } else {
            "TEMP OPERATOR ($adminId · ${Build.BRAND} ${Build.MODEL} · DNA:${dnaHash.take(12)})"
        }

        return SovereignSiliconProof(
            adminId = adminId,
            siliconDnaHash = dnaHash,
            isSovereignIqoo13Silicon = isIqoo13,
            deviceModel = Build.MODEL ?: "Unknown",
            socModel = soc ?: "Unknown",
            roleClaim = roleClaim,
            hardwareSummary = summary,
            hwNonce = nonce,
            hwSignature = sigB64,
            hwCertBase64 = certB64
        )
    }
}
