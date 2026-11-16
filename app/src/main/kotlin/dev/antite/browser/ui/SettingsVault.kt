package dev.antite.browser.ui

import android.app.KeyguardManager
import dev.antite.browser.Browser
import dev.antite.browser.R
import dev.antite.browser.data.Options
import dev.antite.browser.l10n.T

// The parameters of the secure storage: whether opening it asks for the device unlock, when it locks back, what
// happens to the original file and whether the menu has the entry at all.
internal fun vaultSettingsPage(b: Browser) = SettingsPage(T.vaultTitle) { nav ->
    toggle(b, T.vaultAuth, T.vaultAuthSummary, "vaultAuth", true, false)
    choice(b, nav, T.vaultLock, "vaultLock", Options.vaultLocks, 0, false)
    toggle(b, T.vaultDeleteOriginal, T.vaultDeleteOriginalSummary, "vaultDeleteOriginal", true, false)
    toggle(b, T.vaultInMenu, T.vaultInMenuSummary, "vaultInMenu", true, false)
    val secure = b.activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    if (!secure) note(T.vaultNoLock)
}
