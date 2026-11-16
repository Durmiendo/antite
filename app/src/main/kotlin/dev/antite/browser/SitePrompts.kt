package dev.antite.browser

import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.data.SitePerms
import dev.antite.browser.data.Tab
import dev.antite.browser.l10n.T
import dev.antite.engine.AndroidPermissionRequest
import dev.antite.engine.ContentPermissionRequest
import dev.antite.engine.Engine
import dev.antite.engine.MediaPermissionRequest
import dev.antite.engine.Prompt
import dev.antite.engine.SitePermission
import kotlinx.coroutines.launch

internal fun Browser.askSite(icon: Int, host: String, what: String, answer: (Int) -> Unit) {
    var result = -1
    show(UiDialog.Message(
        title = T.siteAsks(host = host, what = what),
        icon = icon,
        stacked = true,
        buttons = listOf(
            DialogButton(T.siteAllowAlways) { result = SitePerms.ALLOW },
            DialogButton(T.siteAllowOnce) { result = Browser.ALLOW_ONCE },
            DialogButton(T.siteDeny) { result = SitePerms.BLOCK },
        ),
        onDismiss = { answer(result) },
    ))
}

internal fun Browser.permIcon(c: SitePerms.Cat): Int = when (c) {
    SitePerms.LOCATION -> R.drawable.ic_location_on
    SitePerms.NOTIFICATIONS -> R.drawable.ic_notifications
    SitePerms.LNA -> R.drawable.ic_lan
    SitePerms.XR -> R.drawable.ic_view_in_ar
    SitePerms.STORAGE -> R.drawable.ic_database
    SitePerms.DRM -> R.drawable.ic_shield_lock
    else -> R.drawable.ic_public
}

internal fun Browser.onContentPermission(t: Tab, r: ContentPermissionRequest) {
    val c = SitePerms.forEngine(r.permission) ?: return r.deny()
    val h = Urls.siteHost(r.origin)
    if (c == SitePerms.AUTOPLAY) {
        r.forgetStoredAnswer()
        if (perms.autoplayAllowed(h, r.permission == SitePermission.AUTOPLAY_AUDIBLE)) r.allow() else r.deny()
        return
    }
    val fromException = perms.exception(c, h) >= 0
    val mode = perms.decide(c, h)
    if (mode != SitePerms.ASK) {
        if (!fromException) r.forgetStoredAnswer()
        if (mode == SitePerms.ALLOW) r.allow() else r.deny()
        return
    }
    askSite(permIcon(c), h, c.request ?: T.sitePermissionGeneric) { answer ->
        if ((answer == SitePerms.ALLOW || answer == SitePerms.BLOCK) && !t.incognito) {
            perms.setException(c, h, answer)
        } else {
            r.forgetStoredAnswer()
        }
        val allow = answer == SitePerms.ALLOW || answer == Browser.ALLOW_ONCE
        if (allow && c == SitePerms.NOTIFICATIONS) activity.requestNotificationPermission(false)
        if (allow) r.allow() else r.deny()
    }
}

internal fun Browser.onMediaPermission(t: Tab, r: MediaPermissionRequest) {
    val h = Urls.siteHost(r.origin)
    val cam = r.camera && perms.decide(SitePerms.CAMERA, h) != SitePerms.BLOCK
    val mic = r.microphone && perms.decide(SitePerms.MIC, h) != SitePerms.BLOCK
    if (!cam && !mic) return r.reject()
    val askCam = cam && perms.decide(SitePerms.CAMERA, h) == SitePerms.ASK
    val askMic = mic && perms.decide(SitePerms.MIC, h) == SitePerms.ASK
    if (!askCam && !askMic) return r.grant(cam, mic)
    val what = when {
        askCam && askMic -> T.siteCameraAndMic
        askCam -> T.permCameraRequest
        else -> T.permMicRequest
    }
    askSite(if (askCam) R.drawable.ic_videocam else R.drawable.ic_mic, h, what) { answer ->
        if ((answer == SitePerms.ALLOW || answer == SitePerms.BLOCK) && !t.incognito) {
            if (askCam) perms.setException(SitePerms.CAMERA, h, answer)
            if (askMic) perms.setException(SitePerms.MIC, h, answer)
        }
        val allow = answer == SitePerms.ALLOW || answer == Browser.ALLOW_ONCE
        r.grant(cam && (allow || !askCam), mic && (allow || !askMic))
    }
}

internal fun Browser.askAndroidPermission(r: AndroidPermissionRequest) {
    val need = r.permissions.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (need.isEmpty()) return r.granted()
    activity.requestPermissions(need) { ok -> if (ok) r.granted() else r.denied() }
}

internal fun Browser.onPrompt(t: Tab, p: Prompt) {
    when (p) {
        is Prompt.Popup -> {
            val ok = perms.decide(SitePerms.POPUPS, Urls.siteHost(t.url)) == SitePerms.ALLOW
            if (ok) p.allow() else {
                p.dismiss()
                toast(T.popupBlocked)
            }
        }
        is Prompt.File -> if (!prefs.bool("vaultInMenu", true)) pickDeviceFiles(p) else {
            var chosen = false
            show(UiDialog.Items(T.uploadFrom, listOf(
                DialogButton(T.uploadFromDevice) { chosen = true; pickDeviceFiles(p) },
                DialogButton(T.vaultTitle) { chosen = true; unlockVault { pickVaultFiles(p) } },
            ), onDismiss = { if (!chosen) p.dismiss() }))
        }
        is Prompt.SaveLogin -> AutofillPrompts.saveLogin(this, p)
        // Like Chrome, saved logins and a strong password are offered above the keyboard; typing ignores them.
        is Prompt.SelectLogin -> if (t !== current) p.dismiss() else {
            dismissLoginOffer()
            loginOffer = p
        }
        is Prompt.SelectCard -> unlockSecrets(T.cardFillAuth, { show(UiDialog.Engine(p)) }, { p.dismiss() })
        is Prompt.SaveCard -> if (prefs.bool("cardsEnabled", true)) show(UiDialog.Engine(p)) else p.dismiss()
        is Prompt.SaveAddress -> if (prefs.bool("addressesEnabled", true)) show(UiDialog.Engine(p)) else p.dismiss()
        else -> show(UiDialog.Engine(p))
    }
}

private fun Browser.pickDeviceFiles(p: Prompt.File) = activity.pickFiles(p.mimeTypes, p.multiple) { uris ->
    if (uris.isNullOrEmpty()) p.dismiss() else p.confirm(uris)
}

// Files of the secure storage that fit the page's accept list; several when the field takes several.
private fun Browser.pickVaultFiles(p: Prompt.File) {
    val fits = mediaVault.items.filter { item -> p.mimeTypes.isEmpty() || p.mimeTypes.any { accepts(it, item) } }
        .sortedByDescending { it.time }
    if (fits.isEmpty()) {
        toast(T.uploadVaultNothing)
        return p.dismiss()
    }
    var chosen = false
    fun send(items: List<dev.antite.browser.data.MediaVault.Item>) {
        chosen = true
        if (items.isEmpty()) return p.dismiss()
        activity.lifecycleScope.launch { p.confirm(Uploads.fromVault(activity, mediaVault, items)) }
    }
    if (p.multiple) show(UiDialog.Checks(T.vaultTitle, fits.map { it.name }, fits.map { false }, T.actionConfirm,
        { checked -> send(fits.filterIndexed { i, _ -> checked[i] }) }, { if (!chosen) p.dismiss() }))
    else show(UiDialog.Items(T.vaultTitle, fits.map { item -> DialogButton(item.name) { send(listOf(item)) } },
        { if (!chosen) p.dismiss() }))
}

// An accept entry: a MIME type, a wildcard like image/*, or an extension like .pdf.
private fun accepts(accept: String, item: dev.antite.browser.data.MediaVault.Item): Boolean {
    val a = accept.trim().lowercase()
    return when {
        a.startsWith(".") -> item.name.lowercase().endsWith(a)
        a.endsWith("/*") -> item.mime.startsWith(a.dropLast(1))
        a == "*/*" || a.isEmpty() -> true
        else -> item.mime.equals(a, true)
    }
}
