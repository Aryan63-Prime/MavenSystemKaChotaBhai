package com.sentinel.admin.data.security

import java.security.MessageDigest
import java.util.Arrays

object AdminCryptoVault {
    /**
     * One-way SHA-256 hex digests of revoked administrator tokens:
     * 1. Legacy Oct 21 ADMIN-001 token (...aYtZD61xgGvPGds18AaXT7iALu57JlyoKmFTWsK7RXs)
     * 2. Superseded ADMIN-002 token (...pH-XBY3rEwPGtzLwF4qOXUostvgekYOlY7wCExM1Khs)
     */
    val REVOKED_TOKEN_SHA256_HASHES: Set<String> = setOf(
        "149537738d6d17a72615aaa70dd1ac0c87700cc3d298cc172f9461983aef54b5",
        "91168630d29f11e36f35c4deb74ac3789d09fc6d02914e48458ab6c666aafb5c"
    )

    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.trim().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isTokenRevoked(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return REVOKED_TOKEN_SHA256_HASHES.contains(sha256Hex(token))
    }

    fun getBootstrapAdminToken(): String = decodeRollingXor(kEncAdmin001Token, 0x5A)

    fun getSecureGatewayUrl(): String = decodeRollingXor(kEncGatewayUrl, 0x3C)

    private fun decodeRollingXor(enc: IntArray, seed: Int): String {
        val chars = CharArray(enc.size)
        for (i in enc.indices) {
            val mask = ((seed + (i * 0x29)) xor 0xC3) and 0xFF
            chars[i] = (enc[i] xor mask).toChar()
        }
        val out = String(chars)
        Arrays.fill(chars, '\u0000')
        return out
    }

    // Sovereign ADMIN-001 Token (Rolling-XOR, seed 0x5A)
    private val kEncAdmin001Token = intArrayOf(
        252, 57, 37, 126, 95, 163, 240, 211, 46, 97, 125, 151, 208, 214, 18, 51, 103, 185, 182, 213, 4, 26, 113, 255, 146, 219, 14, 88, 92, 87, 155, 202, 239, 35, 69, 15, 243, 225, 202, 16, 106, 114, 143, 164, 213, 21, 73, 116, 175, 145, 200, 23, 4, 91, 170, 160, 211, 234, 34, 63, 127, 136, 226, 130, 46, 77, 107, 147, 148, 232, 16, 48, 81, 171, 196, 211, 44, 35, 114, 136, 158, 243, 210, 95, 124, 110, 180, 196, 129, 20, 95, 96, 185, 158, 197, 0, 9, 66, 162, 160, 238, 13, 26, 109, 168, 139, 164, 232, 12, 69, 81, 141, 224, 225, 10, 84, 99, 135, 214, 158, 37, 53, 99, 143, 141, 213, 34, 5, 112, 154, 183, 254, 202, 45, 121, 85, 152, 197, 227, 31, 49, 97, 143, 225, 209, 29, 5, 111, 180, 184, 224, 0, 1, 65, 159, 191, 252, 243, 1, 87, 10, 175, 219, 255, 105, 73, 103, 159, 251, 229, 61, 52, 28, 226, 151, 230, 36, 10, 110, 130, 185, 173, 34, 4, 38, 94, 190, 193, 207, 107, 109, 92, 237, 211, 250, 20, 50, 23, 163, 160, 238, 36, 6, 72, 128, 218, 248, 119, 39, 119, 104, 185, 186, 220, 23, 125, 86
    )

    // Secure Gateway WebSocket URL (Rolling-XOR, seed 0x3C)
    private val kEncGatewayUrl = intArrayOf(
        136, 213, 62, 78, 12, 229, 129, 234, 40, 4, 112, 95, 159, 191, 202, 5, 97, 66, 180, 234, 214, 54, 44, 90, 160, 138, 145, 98, 20, 76, 187, 149, 241, 34, 8, 102, 237, 137, 254, 213, 72, 121, 70
    )
}
