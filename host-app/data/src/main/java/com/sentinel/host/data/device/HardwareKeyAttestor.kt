package com.sentinel.host.data.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HardwareKeyAttestor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val SIGN_KEY_ALIAS = "sentinel_hw_attest_ec_v4"
        private const val ENVELOPE_KEY_ALIAS = "sentinel_hw_aes_gcm_v4"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    init {
        ensureSigningKeyExists()
        ensureEnvelopeKeyExists()
    }

    /**
     * Extracts the runtime SHA-256 digest of this APK's v2/v3 signing certificate.
     * Any apktool repackaging or unauthorized re-signing mutates this digest.
     */
    fun getApkSigningCertSha256(): String {
        val ctx = context ?: return "SENTINEL_STATIC_CONTEXT"
        return try {
            val pm = ctx.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            }
            val rawCert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures?.firstOrNull()?.toByteArray()
            } ?: return "UNSIGNED"
            MessageDigest.getInstance("SHA-256")
                .digest(rawCert)
                .joinToString("") { "%02X".format(it) }
        } catch (_: Throwable) {
            "UNKNOWN_CERT"
        }
    }

    private fun ensureSigningKeyExists() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(SIGN_KEY_ALIAS)) {
                val apkCertHash = getApkSigningCertSha256()
                val challengeBytes = "SENTINEL_V4:$apkCertHash".toByteArray(Charsets.UTF_8)
                val buildSpec = { useStrongBox: Boolean ->
                    KeyGenParameterSpec.Builder(
                        SIGN_KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                setAttestationChallenge(challengeBytes)
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

    private fun ensureEnvelopeKeyExists() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(ENVELOPE_KEY_ALIAS)) {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    ENVELOPE_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                kg.init(spec)
                kg.generateKey()
            }
        } catch (_: Throwable) {}
    }

    fun generateProofOfPossession(nonce: String): String {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(SIGN_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return ""

            val signer = Signature.getInstance("SHA256withECDSA").apply {
                initSign(entry.privateKey)
                update(nonce.toByteArray(Charsets.UTF_8))
            }
            Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }

    fun getAttestationPublicKeyCert(): String {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val chain = ks.getCertificateChain(SIGN_KEY_ALIAS)
            val leaf = chain?.firstOrNull() ?: ks.getCertificate(SIGN_KEY_ALIAS) ?: return ""
            Base64.encodeToString(leaf.encoded, Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }

    fun sealEnvelope(plaintext: String, associatedData: String): String {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = ks.getKey(ENVELOPE_KEY_ALIAS, null) as? SecretKey ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val combined = ByteArray(1 + iv.size + ciphertext.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }
}
