package dev.antite.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.antite.browser.data.Prefs
import dev.antite.browser.l10n.T

// The bar asking to become the default browser: once per start, unless refused for good or postponed.
class DefaultBrowserOffer(private val activity: MainActivity, private val prefs: Prefs, private val toast: (String) -> Unit) {
    companion object {
        const val LATER_MS = 3L * 24 * 3600 * 1000

        fun due(never: Boolean, laterUntil: Long, now: Long, canBe: Boolean, isDefault: Boolean) =
            !never && now >= laterUntil && canBe && !isDefault
    }

    var shown by mutableStateOf(false)
        private set
    private var checked = false

    fun check() {
        if (shown && activity.isDefaultBrowser()) shown = false
        if (checked) return
        checked = true
        shown = due(prefs.bool("defaultBrowserNever", false), prefs.sp.getLong("defaultBrowserLater", 0),
            System.currentTimeMillis(), activity.canBeDefaultBrowser(), activity.isDefaultBrowser())
    }

    fun make() {
        activity.requestDefaultBrowser { ok ->
            toast(if (ok || activity.isDefaultBrowser()) T.defaultBrowserDone else T.defaultBrowserFailed)
        }
    }

    fun answer(yes: Boolean, later: Boolean) {
        shown = false
        when {
            yes -> make()
            later -> prefs.edit { putLong("defaultBrowserLater", System.currentTimeMillis() + LATER_MS) }
            else -> prefs.edit { putBoolean("defaultBrowserNever", true) }
        }
    }
}
