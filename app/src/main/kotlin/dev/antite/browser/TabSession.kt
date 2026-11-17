package dev.antite.browser

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.data.SitePerms
import dev.antite.browser.data.Tab
import dev.antite.browser.l10n.T
import dev.antite.browser.ui.paletteOf
import dev.antite.engine.AndroidPermissionRequest
import dev.antite.engine.ContentPermissionRequest
import dev.antite.engine.ContextInfo
import dev.antite.engine.DownloadRequest
import dev.antite.engine.Engine
import dev.antite.engine.EngineTab
import dev.antite.engine.LoadError
import dev.antite.engine.MediaEvent
import dev.antite.engine.MediaPermissionRequest
import dev.antite.engine.Prompt
import dev.antite.engine.SecurityInfo
import dev.antite.engine.TabCallbacks
import dev.antite.engine.TabOptions
import dev.antite.engine.TranslationState
import kotlinx.coroutines.launch

// Engine events of one tab, turned into the browser's state.
internal class TabSession(private val b: Browser, val t: Tab) : TabCallbacks {
    // A new session first loads about:blank, also when it restores a page; like Firefox, that b.load is ignored.
    private var initialBlank = !Urls.isBlank(t.url)

    private fun skipInitialBlank(url: String): Boolean {
        if (!initialBlank) return false
        if (Urls.isBlank(url)) return true
        initialBlank = false
        return false
    }

    override fun onLocationChange(url: String) {
        if (skipInitialBlank(url)) return
        t.url = url
        b.refreshBookmarked(t)
    }

    override fun errorPage(url: String, error: LoadError): String {
        if (error == LoadError.HTTPS_ONLY) t.httpsOnlyFailed = url
        return ErrorPage.html(url, error, paletteOf(b.prefs.isDark(), b.prefs.isOled()))
    }

    override fun onTitleChange(title: String) {
        t.title = title
        if (!t.incognito) {
            val url = t.url
            val category = t.category
            b.activity.lifecycleScope.launch { b.db.io { b.historyOr(Unit) { title(category, url, title) } } }
        }
        b.scheduleSave()
    }

    override fun onPageStart(url: String) {
        if (skipInitialBlank(url)) return
        if (url != t.httpsOnlyFailed) t.httpsOnlyFailed = null
        t.loading = true
        t.progress = 0
        // A new page starts at the top; until Gecko reports a scroll, pull to refresh stays allowed.
        if (t === b.current) b.pageScrollY = 0
        if (t === b.current) b.toolbarMotion.show()
    }

    override fun onPageStop(success: Boolean) {
        if (initialBlank) return
        t.loading = false
        if (t === b.current) b.pageStops++
        b.captureSoon(t, 1000, true)
    }

    override fun onProgress(percent: Int) {
        t.progress = percent
    }

    override fun onSecurityChange(info: SecurityInfo) {
        t.secure = info.secure
        if (info.secure) t.httpsOnlyFailed = null
        t.securityHost = info.host
        t.issuer = info.issuer
    }

    override fun onNavigationState(canGoBack: Boolean, canGoForward: Boolean) {
        t.canGoBack = canGoBack
        t.canGoForward = canGoForward
    }

    override fun onStateChange(state: String) {
        if (t.incognito) return
        t.state = state
        b.scheduleSave()
    }

    override fun onFirstPaint() {
        if (b.restoreCover === t) b.restoreCover = null
        b.captureSoon(t, 1500, false)
    }

    override fun onScroll(y: Int) {
        b.onScroll(t, y)
    }

    override fun onFullscreen(full: Boolean) {
        if (t === b.current) b.activity.setFullscreen(full)
    }

    override fun onFocusRequest() {
        if (t !== b.current) b.main.post { b.switchTo(t) }
    }

    override fun onCloseRequest() = b.closeTab(t)

    override fun onCrash() = b.recover(t)

    override fun onNewTab(url: String, create: (TabOptions, TabCallbacks) -> EngineTab): EngineTab {
        val child = Tab.create(t.incognito)
        child.url = url
        child.parentId = t.id
        // The engine requires the same storage for a window a page opens.
        child.container = t.container
        child.desktop = b.prefs.bool("desktopDefault", false)
        b.tabs.add(b.tabs.indexOf(t) + 1, child)
        val tab = create(b.options(child, false), TabSession(b, child))
        child.engineTab = tab
        b.main.post { b.switchTo(child) }
        return tab
    }

    override fun allowJavascript(url: String) = b.perms.decide(SitePerms.JS, Urls.siteHost(url)) == SitePerms.ALLOW

    override fun onVisit(url: String, title: String?): Boolean {
        if (t.incognito || !Urls.isWeb(url)) return false
        val name = if (t.url == url) t.title else null
        val category = t.category
        b.activity.lifecycleScope.launch { b.db.io { b.historyOr(Unit) { visit(category, url, name) } } }
        return true
    }

    override fun isVisited(urls: List<String>, done: (BooleanArray) -> Unit) {
        val category = t.category
        b.activity.lifecycleScope.launch { done(b.db.io { b.historyOr(BooleanArray(urls.size)) { visited(category, urls) } }) }
    }

    override fun onContextMenu(info: ContextInfo) {
        b.showContextMenu(t, info)
    }

    override fun onDownload(request: DownloadRequest) = b.downloads.start(request, t)

    override fun onPageMeta(pageUrl: String, iconUrl: String?, themeColor: Int?) {
        if (t.incognito) return
        // Only the tab's own site sets its icon: a late message from the page before a navigation must not.
        val h = Urls.host(t.url)
        if (h.isEmpty() || Urls.host(pageUrl) != h) return
        val page = Uri.parse(t.url)
        b.icons.setTheme(h, themeColor)
        b.icons.onPageIcon(h, iconUrl, "${page.scheme}://${page.host}")
    }

    override fun onPrompt(prompt: Prompt) = b.onPrompt(t, prompt)

    override fun onContentPermission(request: ContentPermissionRequest) = b.onContentPermission(t, request)

    override fun onMediaPermission(request: MediaPermissionRequest) = b.onMediaPermission(t, request)

    override fun onAndroidPermission(request: AndroidPermissionRequest) = b.askAndroidPermission(request)

    override fun onTranslationOffer() {
        if (t !== b.current || b.translation == null || !b.prefs.bool("b.translateOffer", true)) return
        val from = t.docLang?.let { Urls.langName(it) + " → " } ?: ""
        b.translateOffer = T.translateOffer(languages = from + Urls.langName(b.prefs.translateTarget))
        b.main.postDelayed({ b.translateOffer = null }, 15000)
    }

    override fun onTranslationState(state: TranslationState) {
        state.pageLanguage?.let { t.docLang = it }
        t.translated = state.translated
        if (state.error != null && t === b.current) b.toast(T.translateError(reason = state.error))
    }

    override fun onMedia(event: MediaEvent) {
        b.activity.pip.onMedia(event, t === b.current)
        b.media.onEvent(event, t.title, Urls.host(t.url), b.icons.get(Urls.host(t.url))) {
            b.activity.requestNotificationPermission(true)
        }
    }
}
