package dev.antite.browser

import android.app.KeyguardManager
import android.content.Intent
import dev.antite.browser.data.Entry
import dev.antite.browser.l10n.T
import dev.antite.browser.service.Downloads
import dev.antite.browser.ui.vaultImported

// The secure storage behind its lock: the device unlock or the key ring password, its closing on return, and files
// coming into it from downloads and other apps.
class VaultAccess(private val b: Browser) {
    fun relock() {
        if (b.locks.returned()) b.lockKeys()
    }

    // A device without a screen lock has nothing to authenticate against; the vault opens and the pages warn about it.
    fun needsAuth(): Boolean =
        b.prefs.bool("vaultAuth", true) && b.activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    // Open for the interface: unlocked, or there is nothing to unlock with, so no locked screen is shown. With a
    // password key mode only the password opens it.
    val open: Boolean get() = if (b.ring.mode.usesPassword) b.mediaVault.opened else b.locks.vaultUnlocked || !needsAuth()

    fun unlock(then: () -> Unit = {}) {
        val locks = b.locks
        if (b.ring.mode.usesPassword) {
            if (b.mediaVault.opened) return then()
            return b.askPassword(T.keysUnlock, { locks.vaultUnlocked = true; then() })
        }
        if (locks.vaultUnlocked) return then()
        if (!needsAuth()) {
            locks.vaultUnlocked = true
            return then()
        }
        Auth.require(b.activity, T.vaultUnlockReason, { locks.vaultUnlocked = true; then() }, {})
    }

    // A finished download moves in: the file is copied into the app's private folder, the original is dropped.
    fun add(e: Entry) {
        if (!Downloads.done(e)) return
        b.mediaVault.add(e, b.prefs.bool("vaultDeleteOriginal", true)) { ok ->
            b.downloads.revision.intValue++
            b.toast(if (ok) T.vaultMovedIn else T.vaultMoveFailed)
        }
    }

    // Files shared by another app go into the secure storage. Only content: addresses: a file: one could name the
    // browser's own files.
    fun takeShared(intent: Intent) {
        val clip = intent.clipData
        // The typed getters need Android 13; one file comes as a single value, several as a list.
        @Suppress("DEPRECATION")
        val extra = when (val v = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is android.net.Uri -> listOf(v)
            is List<*> -> v.filterIsInstance<android.net.Uri>()
            else -> emptyList()
        }
        val uris = ((0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri } + extra)
            .filter { it.scheme == "content" }.distinct()
        if (uris.isEmpty()) return b.toast(T.vaultNothingShared)
        b.toast(T.vaultAdding(count = uris.size))
        b.mediaVault.import(uris, null, false) { added, failed -> b.vaultImported(added, failed) }
    }
}
