package dev.antite.browser

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import dev.antite.browser.l10n.T

object Auth {
    private const val GRACE_MS = 60_000L
    private var lastOk = 0L
    var onUnavailable: (String) -> Unit = {}

    fun require(a: Activity, reason: String, ok: () -> Unit, cancelled: () -> Unit) {
        if (lastOk != 0L && SystemClock.elapsedRealtime() - lastOk < GRACE_MS) return ok()
        val km = a.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            onUnavailable(T.authNeedScreenLock)
            return cancelled()
        }
        BiometricPrompt.Builder(a)
            .setTitle("Antite")
            .setSubtitle(reason)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
            .authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    lastOk = SystemClock.elapsedRealtime()
                    ok()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) = cancelled()
            })
    }
}
