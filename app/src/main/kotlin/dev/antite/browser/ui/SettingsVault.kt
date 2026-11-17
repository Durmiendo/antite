package dev.antite.browser.ui

import android.app.KeyguardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.antite.browser.Browser
import dev.antite.browser.FormField
import dev.antite.browser.changePassword
import dev.antite.browser.data.Cipher256
import dev.antite.browser.data.KeyRing
import dev.antite.browser.data.Options
import dev.antite.browser.data.Secure
import dev.antite.browser.l10n.T
import dev.antite.browser.ring
import dev.antite.browser.setCipher
import dev.antite.browser.setKeyMode

// The parameters of the secure storage: how it is entered, when it locks back, what happens to the original file,
// the menu entry, and the keys and cipher behind it and the saved passwords.
internal fun vaultSettingsPage(b: Browser) = SettingsPage(T.vaultTitle) { nav ->
    val secure = b.activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    if (!b.ring.mode.usesPassword) {
        mark(T.vaultAuth, null, "toggle:vaultAuth")
        item(key = "toggle:vaultAuth") {
            Marked("toggle:vaultAuth") {
                var on by remember { mutableIntStateOf(if (b.prefs.bool("vaultAuth", true)) 1 else 0) }
                SettingRow(T.vaultAuth, null, onClick = {
                    on = 1 - on
                    b.prefs.edit { putBoolean("vaultAuth", on == 1) }
                }, toggled = on == 1, info = if (secure) T.vaultAuthSummary else T.vaultNoLock,
                    badge = if (secure) null else T.vaultNoLockBadge, trailing = { SwitchMark(on == 1) })
            }
        }
    }
    choice(b, nav, T.vaultLock, "vaultLock", Options.vaultLocks, 0, false,
        Custom(T.customMinutesField(min = 1, max = 1440), FormField.Kind.NUMBER, { Options.customVaultLock(it) != null }) {
            T.vaultLockAfter(minutes = it)
        })
    infoToggle(b, T.vaultDeleteOriginal, T.vaultDeleteOriginalSummary, "vaultDeleteOriginal", true)
    toggle(b, T.vaultInMenu, null, "vaultInMenu", true, false)
}

internal fun keysSummary(b: Browser) = "${cipherName(Secure.cipher(b.activity))} · ${modeName(b.ring.mode)}"

private fun modeName(m: KeyRing.Mode) = when (m) {
    KeyRing.Mode.DEVICE -> T.keysModeDevice
    KeyRing.Mode.PASSWORD -> T.keysModePassword
    KeyRing.Mode.BOTH -> T.keysModeBoth
}

private fun modeInfo(m: KeyRing.Mode) = when (m) {
    KeyRing.Mode.DEVICE -> T.keysModeDeviceInfo
    KeyRing.Mode.PASSWORD -> T.keysModePasswordInfo
    KeyRing.Mode.BOTH -> T.keysModeBothInfo
}

private fun cipherName(c: Cipher256) = when (c) {
    Cipher256.AES -> T.keysCipherAes
    Cipher256.XCHACHA -> T.keysCipherXchacha
    Cipher256.CASCADE -> T.keysCipherCascade
}

// A switch whose explanation sits behind an info button instead of a summary line.
internal fun androidx.compose.foundation.lazy.LazyListScope.infoToggle(b: Browser, title: String, info: String, key: String, def: Boolean) {
    mark(title, info, "toggle:$key")
    item(key = "toggle:$key") {
        Marked("toggle:$key") {
            var on by remember { mutableIntStateOf(if (b.prefs.bool(key, def)) 1 else 0) }
            SettingRow(title, null, onClick = {
                on = 1 - on
                b.prefs.edit { putBoolean(key, on == 1) }
            }, toggled = on == 1, info = info, trailing = { SwitchMark(on == 1) })
        }
    }
}

// Shared by the secure storage, the saved passwords, addresses and cards.
internal fun keysPage(b: Browser) = SettingsPage(T.keysTitle) { nav ->
    Secure.revision.intValue
    mark(T.keysMode, null, "keys:mode")
    item(key = "keys:mode") {
        Marked("keys:mode") {
            val m = remember(Secure.revision.intValue) { b.ring.mode }
            SettingRow(T.keysMode, modeName(m), info = modeInfo(m),
                badge = if (m.usesDevice) T.keysEraseYes else T.keysEraseNo, onClick = {
                    val modes = KeyRing.Mode.entries
                    nav.push(T.keysMode) { n ->
                        RadioPage(b, n, T.keysMode, modes.map { modeName(it) }, { modes.indexOf(b.ring.mode) }) { i ->
                            n.pop {}
                            if (modes[i] != b.ring.mode) b.setKeyMode(modes[i]) { Secure.revision.intValue++ }
                        }
                    }
                })
        }
    }
    if (b.ring.mode.usesPassword) action(T.keysPasswordChange, null, "keys:password") { b.changePassword() }
    mark(T.keysCipher, T.keysCipherInfo, "keys:cipher")
    item(key = "keys:cipher") {
        Marked("keys:cipher") {
            val c = remember(Secure.revision.intValue) { Secure.cipher(b.activity) }
            SettingRow(T.keysCipher, cipherName(c), info = T.keysCipherInfo, onClick = {
                val all = Cipher256.entries
                nav.push(T.keysCipher) { n ->
                    RadioPage(b, n, T.keysCipher, all.map { cipherName(it) }, { all.indexOf(Secure.cipher(b.activity)) }) { i ->
                        n.pop {}
                        if (all[i] != Secure.cipher(b.activity)) b.setCipher(all[i]) { Secure.revision.intValue++ }
                    }
                }
            })
        }
    }
    infoToggle(b, T.keysWipe, T.keysWipeInfo, Secure.PREF_WIPE, false)
}
