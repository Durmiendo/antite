package dev.antite.browser

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.data.Tab
import dev.antite.browser.l10n.T
import dev.antite.engine.ContextInfo
import dev.antite.engine.DataKind
import dev.antite.engine.EngineTab
import kotlinx.coroutines.launch

fun Browser.showFind() {
    findVisible = true
    findResult = ""
}

fun Browser.hideFind() {
    if (!findVisible) return
    findVisible = false
    current?.engineTab?.clearFind()
}

fun Browser.find(text: String?, backwards: Boolean = false) {
    val tab = current?.engineTab ?: return
    if (text != null && text.isEmpty()) {
        tab.clearFind()
        findResult = ""
        return
    }
    tab.find(text, backwards) { cur, total -> findResult = if (total > 0) "$cur/$total" else "0/0" }
}

fun Browser.toggleDesktop(t: Tab) {
    t.desktop = !t.desktop
    val s = ensureSession(t)
    s.desktopMode = t.desktop
    s.reload()
    scheduleSave()
}

fun Browser.toggleDark() {
    prefs.edit { putInt("theme", if (prefs.isDark()) 1 else 2) }
    applyEngineSettings()
}

internal fun Browser.refreshBookmarked(t: Tab) {
    if (!Urls.isWeb(t.url)) {
        t.bookmarked = false
        return
    }
    val url = t.url
    activity.lifecycleScope.launch {
        val b = db.io { isBookmarked(url) }
        if (url == t.url) t.bookmarked = b
    }
}

fun Browser.toggleBookmark(t: Tab) {
    val url = t.url
    val title = t.title
    val add = !t.bookmarked
    t.bookmarked = add
    activity.lifecycleScope.launch { db.io { if (add) addBookmark(url, title) else removeBookmark(url) } }
    toast(if (add) T.bookmarkAdded else T.bookmarkRemoved)
}

fun Browser.bookmarkAllTabs() {
    val pages = tabs.filter { !it.incognito && Urls.isWeb(it.url) }
    val data = pages.map { it.url to it.title }
    activity.lifecycleScope.launch { db.io { data.forEach { addBookmark(it.first, it.second) } } }
    pages.forEach { it.bookmarked = true }
    toast(T.bookmarksAddedTabs(count = pages.size))
}

fun Browser.translate(t: Tab) {
    if (!Urls.isWeb(t.url)) return
    val s = ensureSession(t)
    if (t.translated) {
        s.restoreOriginal { t.translated = false }
        return
    }
    val to = prefs.translateTarget
    val from = t.docLang
    if (!from.isNullOrEmpty()) {
        runTranslation(s, from, to)
        return
    }
    val tr = translation ?: return toast(T.translateUnavailable)
    tr.supported({ fromList, _ ->
        show(UiDialog.Items(T.translatePageLanguage, fromList.map { l -> DialogButton(l.name) { runTranslation(s, l.code, to) } }))
    }, { toast(T.translateUnavailable) })
}

internal fun Browser.runTranslation(s: EngineTab, from: String, to: String) {
    if (from == to) return toast(T.translateSameLanguage)
    toast(T.translateInProgress)
    s.translate(from, to) { err -> if (err != null) toast(T.translateFailed(reason = err)) }
}

fun Browser.clearData(cache: Boolean, siteData: Boolean, permissions: Boolean, history: Boolean) {
    if (cache) icons.clear()
    if (history) {
        activity.lifecycleScope.launch {
            db.io {
                clearHistory()
                clearClosed()
            }
        }
        tabs.forEach {
            it.engineTab?.purgeHistory()
            it.state = null
        }
        scheduleSave()
    }
    val kinds = buildSet {
        if (cache) add(DataKind.CACHE)
        if (siteData) add(DataKind.COOKIES_AND_SITE_DATA)
        if (permissions) add(DataKind.PERMISSIONS)
    }
    engine.storage.clear(kinds) { ok -> toast(if (ok) T.clearDataDone else T.clearDataFailed) }
}

fun Browser.openExtensionsMenu() {
    val ext = engine.extensions
    val list = ext?.list().orEmpty()
    if (ext == null || list.isEmpty()) return toast(T.extensionsNone)
    show(UiDialog.Items(T.extensionsTitle, list.map { e ->
        DialogButton(e.name) {
            if (e.hasAction) ext.clickAction(e.id) else e.optionsUrl?.let { openTab(it, false, current, true) }
        }
    }))
}

fun Browser.openUboDashboard() {
    val ubo = engine.extensions?.list()?.firstOrNull { it.id == Browser.UBO_ID }?.optionsUrl
    if (ubo == null) return toast(T.uboNotInstalled)
    overlay = Overlay.NONE
    openTab(ubo, false, current, true)
}

fun Browser.closePopup(tab: EngineTab) {
    if (extensionPopup === tab) extensionPopup = null
    tab.close()
}

fun Browser.restoreClosed(id: Long, url: String, title: String?, state: String?) {
    overlay = Overlay.NONE
    activity.lifecycleScope.launch { db.io { deleteClosed(id) } }
    val t = Tab.create(false)
    t.url = url
    t.title = title ?: ""
    t.state = state
    tabs.add(t)
    switchTo(t)
    scheduleSave()
}

fun Browser.clearDataDialog() = show(UiDialog.Checks(
    T.clearDataTitle,
    listOf(T.clearDataCache, T.clearDataCookies, T.clearDataPermissions, T.clearDataHistory),
    listOf(true, true, false, false),
    T.actionDelete,
    { c -> clearData(c[0], c[1], c[2], c[3]) },
))

fun Browser.pageInfo() {
    val t = current ?: return
    val host = t.securityHost.ifEmpty { Uri.parse(t.url).host }
    val text = buildString {
        append(t.url).append("\n\n")
        if (t.url.startsWith("https://")) {
            append(if (t.secure) T.pageInfoSecure else T.pageInfoInsecure)
            if (t.issuer.isNotEmpty()) append("\n").append(T.pageInfoCertificate(issuer = issuerName(t.issuer)))
        } else if (t.url.startsWith("http://")) {
            append(T.pageInfoHttp)
        }
        if (t.incognito) append("\n\n").append(T.pageInfoIncognito)
    }
    show(UiDialog.Message(
        title = host ?: T.menuPageInfo,
        message = text,
        buttons = buildList {
            add(DialogButton(T.pageInfoCopyUrl) { activity.copy(t.url) })
            if (Urls.isWeb(t.url)) add(DialogButton(T.pageInfoSiteSettings) { showSettings(Urls.siteHost(t.url)) })
            add(DialogButton(T.actionConfirm))
        },
    ))
}

internal fun Browser.issuerName(dn: String): String {
    for (prefix in listOf("O=", "CN=")) {
        dn.split(',').map { it.trim() }.firstOrNull { it.startsWith(prefix) }?.let { return it.removePrefix(prefix) }
    }
    return dn
}

fun Browser.showContextMenu(t: Tab, info: ContextInfo) {
    val items = mutableListOf<DialogButton>()
    val link = info.linkUrl
    val src = info.srcUrl
    if (link != null) {
        items += DialogButton(T.openInNewTab) {
            openTab(link, t.incognito, t, false)
            toast(T.contextTabOpened)
        }
        if (!t.incognito) items += DialogButton(T.contextOpenIncognito) { openTab(link, true, null, true) }
        items += DialogButton(T.contextCopyLinkAddress) { activity.copy(link) }
        info.linkText?.takeIf { it.isNotEmpty() }?.let { text ->
            items += DialogButton(T.contextCopyLinkText) { activity.copy(text) }
        }
        items += DialogButton(T.contextShareLink) { activity.share(link, null) }
    }
    if (src != null && info.isImage) {
        items += DialogButton(T.contextOpenImage) { openTab(src, t.incognito, t, false) }
        items += DialogButton(T.contextDownloadImage) {
            engine.download(src, t.url, t.incognito) { r ->
                if (r != null) downloads.start(r, prefs.bool("dlAsk", false)) else toast(T.contextDownloadImageFailed)
            }
        }
        items += DialogButton(T.contextCopyImageAddress) { activity.copy(src) }
    }
    if (items.isNotEmpty()) show(UiDialog.Items(link ?: src, items))
}
