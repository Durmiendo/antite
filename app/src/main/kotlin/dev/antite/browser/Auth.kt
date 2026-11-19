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
    // Per reason: unlocking incognito must not open passwords or the vault for the next minute.
    private val lastOk = HashMap<String, Long>()
    var onUnavailable: (String) -> Unit = {}

    // strict: for a key of the device key store bound to the unlock - asked every time, and only what the key store
    // accepts (a strong biometric or the screen lock).
    fun require(a: Activity, reason: String, ok: () -> Unit, cancelled: () -> Unit, strict: Boolean = false) {
        val last = lastOk[reason]
        if (!strict && last != null && SystemClock.elapsedRealtime() - last < GRACE_MS) return ok()
        val km = a.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            onUnavailable(T.authNeedScreenLock)
            return cancelled()
        }
        BiometricPrompt.Builder(a)
            .setTitle("Antite")
            .setSubtitle(reason)
            .setAllowedAuthenticators(
                (if (strict) BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK) or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
            .authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    lastOk[reason] = SystemClock.elapsedRealtime()
                    ok()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) = cancelled()
            })
    }
}
