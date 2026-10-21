package com.sentinel.admin.ui.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricPromptHelper {

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Authorize Critical Command",
        subtitle: String = "Verify identity to execute high-privilege operation",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("Biometric authentication failed")
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }

    fun authenticateIfAvailable(
        context: android.content.Context,
        title: String = "Authorize Critical Command",
        subtitle: String = "Verify identity to execute high-privilege operation",
        onAuthorized: () -> Unit
    ) {
        val activity = context as? FragmentActivity
        if (activity != null) {
            val canAuth = try {
                BiometricManager.from(context).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ) == BiometricManager.BIOMETRIC_SUCCESS
            } catch (_: Exception) {
                false
            }
            if (canAuth) {
                authenticate(activity, title, subtitle, onSuccess = onAuthorized, onError = {})
                return
            }
        }
        onAuthorized()
    }
}
