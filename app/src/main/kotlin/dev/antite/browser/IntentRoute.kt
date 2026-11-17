package dev.antite.browser

import android.content.Intent

// What an intent from another app asks the browser for. Only http and https addresses open: an explicit intent passes
// any scheme past the manifest filter, and javascript: would run in the open page, file: could read the browser's own
// files. needTab: the browser has just started and must show some tab.
sealed interface IntentRoute {
    data class Share(val needTab: Boolean) : IntentRoute
    data class Unsupported(val scheme: String?, val needTab: Boolean) : IntentRoute
    // A link to view may go into the current tab, by the "links from other apps" setting; one without an action (a
    // launcher shortcut) always opens a new tab.
    data class Open(val url: String, val view: Boolean) : IntentRoute
    data object NewTab : IntentRoute
    data object Nothing : IntentRoute
}

fun routeIntent(action: String?, data: String?, scheme: String?, needTab: Boolean): IntentRoute = when {
    action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE -> IntentRoute.Share(needTab)
    data != null && scheme?.lowercase() !in setOf("http", "https") -> IntentRoute.Unsupported(scheme, needTab)
    data != null && action == Intent.ACTION_VIEW -> IntentRoute.Open(data, view = true)
    data != null && action == null -> IntentRoute.Open(data, view = false)
    needTab -> IntentRoute.NewTab
    else -> IntentRoute.Nothing
}
