package com.sentinel.admin.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sentinel.admin.data.security.AdminCryptoVault
import com.sentinel.admin.domain.session.SessionPreferences

/**
 * Encrypted implementation of [SessionPreferences].
 *
 * Uses EncryptedSharedPreferences backed by Android Keystore.
 */
class SessionPreferencesImpl(context: Context) : SessionPreferences {

    companion object {
        private const val PREFS_NAME = "sentinel_admin_session"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_REMEMBER_ME = "remember_me"

        val DEFAULT_SERVER_URL: String
            get() = AdminCryptoVault.getSecureGatewayUrl()
    }

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override var serverUrl: String?
        get() {
            val url = prefs.getString(KEY_SERVER_URL, null)
            return if (!url.isNullOrBlank()) url else DEFAULT_SERVER_URL
        }
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    override var rememberMe: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_ME, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_ME, value).apply()

    override fun clear() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_REMEMBER_ME)
            .apply()
    }
}
