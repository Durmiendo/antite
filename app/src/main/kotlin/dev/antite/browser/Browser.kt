package dev.antite.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.data.Db
import dev.antite.browser.data.Entry
import dev.antite.browser.engine.EngineUpdates
import dev.antite.browser.data.IconCache
import dev.antite.browser.data.Options
import dev.antite.browser.data.Prefs
import dev.antite.browser.data.Site
import dev.antite.browser.data.SitePerms
import dev.antite.browser.data.Tab
import dev.antite.browser.data.TabStore
import dev.antite.browser.data.Vault
import dev.antite.browser.l10n.T
import dev.antite.browser.service.AutofillBridge
import dev.antite.browser.service.Downloads
import dev.antite.browser.service.MediaPlayback
import dev.antite.browser.service.WebNotifications
import dev.antite.browser.transfer.TransferActivity
import dev.antite.browser.ui.DarkPalette
import dev.antite.browser.ui.LightPalette
import dev.antite.browser.ui.Motion
import dev.antite.engine.AndroidPermissionRequest
import dev.antite.engine.ContentPermissionRequest
import dev.antite.engine.ContextInfo
import dev.antite.engine.DownloadRequest
import dev.antite.engine.Engine
import dev.antite.engine.EngineTab
import dev.antite.engine.EngineView
import dev.antite.engine.LoadError
import dev.antite.engine.MediaEvent
import dev.antite.engine.MediaPermissionRequest
import dev.antite.engine.Prompt
import dev.antite.engine.SecurityInfo
import dev.antite.engine.TabCallbacks
import dev.antite.engine.TabOptions
import dev.antite.engine.TranslationState
import kotlinx.coroutines.launch

enum class Overlay { NONE, SWITCHER, SETTINGS, LIBRARY }

enum class LibraryKind { HISTORY, BOOKMARKS, CLOSED, DOWNLOADS }

class Browser(val activity: MainActivity, val engine: Engine) {
    companion object {
        internal const val ALLOW_ONCE = 4
        const val UBO_ID = "uBlock0@raymondhill.net"

        // Page translation is off until it works well (user, 10.11.2026); this one switch brings it back.
        const val TRANSLATION_ENABLED = false
        private const val UBO_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    }

    val prefs = Prefs(activity)
    val db = Db.get(activity)
    val vault = Vault.get(activity)
    val perms = SitePerms(activity, prefs, engine)
    val icons = IconCache(activity, engine) { prefs.bool("siteIcons", true) }
    private val store = TabStore(activity)
    val downloads = Downloads(this)
    val engineNews = EngineNews(this)
    private val media = MediaPlayback(activity)
    private val main = Handler(Looper.getMainLooper())
    private val saveTask = Runnable { saveNow() }

    val tabs = mutableStateListOf<Tab>()
    var current by mutableStateOf<Tab?>(null)
        private set

    var overlay by mutableStateOf(Overlay.NONE)
    var libraryKind by mutableStateOf(LibraryKind.HISTORY)
    var settingsSite by mutableStateOf<String?>(null)
    var settingsOpenCount by mutableStateOf(0)
    var switcherIncognito by mutableStateOf(false)

    // Where a full-screen page comes from in the window animation style: the last touch in the browser's own interface
    // and the tab card picked in the switcher. Read once when a page starts opening or closing, so not state.
    var lastTap = Offset.Unspecified
    var lastTapAt = 0L
    var pickedCard: LayoutCoordinates? = null

    val dialogs = mutableStateListOf<UiDialog>()
    val messages = mutableStateListOf<String>()

    var hidden by mutableFloatStateOf(0f)
    var fullscreen by mutableStateOf(false)
    var pip by mutableStateOf(false)
    var urlFocused by mutableStateOf(false)
    var findVisible by mutableStateOf(false)
    var findResult by mutableStateOf("")
    var translateOffer by mutableStateOf<String?>(null)
    val translation get() = if (TRANSLATION_ENABLED) engine.translation else null
    var defaultBrowserOffer by mutableStateOf(false)
    var loginOffer by mutableStateOf<Prompt.SelectLogin?>(null)
    private var defaultBrowserChecked = false
    var menuOpen by mutableStateOf(false)
    var suggestQuery by mutableStateOf("")
    var suggestFill by mutableStateOf<String?>(null)
    var ntpRevision by mutableStateOf(0)
    // Kept here, so the new tab page shows its tiles from its first frame and only refreshes them.
    var ntpPins by mutableStateOf(emptyList<Entry>())
    var ntpFrequent by mutableStateOf(emptyList<Site>())
    var extensionPopup by mutableStateOf<EngineTab?>(null)

    var engineView: EngineView? = null
    var toolbarHeightPx = 0f
    var navDeadZonePx = 0f

    // The navigation bar hides with the fully hidden toolbar and comes back only after the toolbar has come out by
    // navDeadZonePx, so a small scroll up does not pop it up.
    var barsHidden by mutableStateOf(false)
        private set
    private var lastScrollY = -1
    val toolbarMotion = ToolbarMotion(this)

    init {
        Motion.setSpeed(prefs.int(Motion.PREF, Motion.DEFAULT_SPEED))
        Motion.setStyle(prefs.int(Motion.STYLE_PREF, Motion.CHROME))
        Auth.onUnavailable = ::toast
        perms.wipeLegacyOnce()
        applyEngineSettings()
        EngineUpdates.schedule(activity)
        activity.lifecycleScope.launch { loadNtp() }
        engine.setAutofillStore(AutofillBridge(vault, activity.lifecycleScope))
        engine.setNotificationHandler(WebNotifications(activity))
        engine.extensions?.let { ext ->
            ext.ensureInstalled(UBO_ID, UBO_URL)
            ext.setPopupHandler { create ->
                val holder = arrayOfNulls<EngineTab>(1)
                val tab = create(PopupCallbacks { holder[0]?.let { closePopup(it) } })
                holder[0] = tab
                extensionPopup = tab
            }
        }
    }

    suspend fun loadNtp() {
        val hidden = prefs.stringSet("hiddenHosts")
        val count = Options.frequentSites[prefs.index("ntpFrequent", Options.frequentSites, 3)].value
        val (pins, frequent) = db.io {
            val list = pins()
            list to frequent(hidden + list.map { Urls.host(it.url) }, count)
        }
        ntpPins = pins
        ntpFrequent = frequent
    }

    fun applyEngineSettings() = engine.applySettings(prefs.engineSettings(perms, engine.backgroundServices))

    fun toast(text: String) {
        messages += text
    }

    fun show(dialog: UiDialog) {
        dialogs += dialog
    }

    fun confirm(text: String, button: String, action: () -> Unit) = show(
        UiDialog.Message(message = text, buttons = listOf(DialogButton(T.actionCancel), DialogButton(button, action))),
    )

    // ---------------------------------------------------------------- tabs

    fun restore() {
        val loaded = store.load()
        tabs.addAll(loaded.tabs)
        val sel = tabs.firstOrNull { it.id == loaded.selected } ?: tabs.lastOrNull()
        if (sel != null) switchTo(sel)
    }

    fun incognitoCount(incognito: Boolean) = tabs.count { it.incognito == incognito }

    fun newTabAndFocus(incognito: Boolean) {
        openTab("about:blank", incognito, null, true)
        urlFocused = true
    }

    fun openTab(url: String, incognito: Boolean, parent: Tab?, select: Boolean): Tab {
        val t = Tab.create(incognito)
        t.url = url
        t.desktop = prefs.bool("desktopDefault", false)
        if (parent != null) t.parentId = parent.id
        val idx = if (parent != null) tabs.indexOf(parent) + 1 else tabs.size
        tabs.add(idx.coerceIn(0, tabs.size), t)
        if (select) switchTo(t) else {
            ensureSession(t)
            trimSessions()
        }
        scheduleSave()
        return t
    }

    private fun options(t: Tab, withState: Boolean) = TabOptions(
        privateMode = t.incognito,
        desktopMode = t.desktop,
        javascript = perms.decide(SitePerms.JS, Urls.siteHost(t.url)) == SitePerms.ALLOW,
        state = if (withState) t.state else null,
        url = t.url,
    )

    fun ensureSession(t: Tab): EngineTab =
        t.engineTab ?: engine.createTab(options(t, true), TabSession(t)).also { t.engineTab = it }

    fun switchTo(t: Tab) {
        val prev = current
        if (prev != null && prev !== t) prev.engineTab?.setActive(false)
        urlFocused = false
        current = t
        t.lastUsed = System.currentTimeMillis()
        val s = ensureSession(t)
        engineView?.show(s)
        s.setActive(true)
        lastScrollY = -1
        setToolbarHidden(0f)
        hideFind()
        translateOffer = null
        dismissLoginOffer()
        trimSessions()
        refreshBookmarked(t)
        scheduleSave()
    }

    fun attachView(view: EngineView) {
        engineView = view
        current?.let { view.show(ensureSession(it)) }
    }

    private fun trimSessions() {
        val live = tabs.filter { it.live && !it.incognito && it !== current }.sortedBy { it.lastUsed }
        val max = Options.liveTabs[prefs.index("liveTabs", Options.liveTabs, 2)].value
        if (live.size < max) return
        for (t in live.take(live.size - max + 1)) {
            t.engineTab?.close()
            t.engineTab = null
        }
    }

    fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        if (t === current) engineView?.show(null)
        t.engineTab?.close()
        t.engineTab = null
        store.deleteThumb(t.id)
        if (!t.incognito && !Urls.isBlank(t.url)) {
            val url = t.url
            val title = t.title
            val state = t.state
            activity.lifecycleScope.launch { db.io { addClosed(url, title, state) } }
        }
        if (t === current) {
            current = null
            val next = nextAfterClose(t, i)
            when {
                next != null -> switchTo(next)
                overlay == Overlay.SWITCHER -> {}
                else -> newTabAndFocus(false)
            }
        }
        scheduleSave()
    }

    private fun nextAfterClose(closed: Tab, index: Int): Tab? {
        tabs.firstOrNull { it.id == closed.parentId && it.incognito == closed.incognito }?.let { return it }
        for (k in (minOf(index, tabs.size) - 1) downTo 0) if (tabs[k].incognito == closed.incognito) return tabs[k]
        for (k in index until tabs.size) if (tabs[k].incognito == closed.incognito) return tabs[k]
        return tabs.filter { !it.incognito }.maxByOrNull { it.lastUsed }
    }

    fun moveTab(t: Tab, toIndex: Int) {
        tabs.remove(t)
        tabs.add(toIndex.coerceIn(0, tabs.size), t)
        scheduleSave()
    }

    fun loadThumb(t: Tab) = store.loadThumb(t)

    fun scheduleSave() {
        main.removeCallbacks(saveTask)
        main.postDelayed(saveTask, 1500)
    }

    fun saveNow() {
        main.removeCallbacks(saveTask)
        store.save(tabs, current)
    }

    fun captureThumb(t: Tab?) {
        if (t == null || t !== current || overlay == Overlay.SWITCHER) return
        engineView?.capture { b ->
            if (b == null) return@capture
            t.thumb = Bitmap.createScaledBitmap(b, maxOf(1, b.width / 3), maxOf(1, b.height / 3), true)
            store.saveThumb(t)
        }
    }

    private fun captureSoon(t: Tab, delayMs: Long, onlyWhenLoaded: Boolean) {
        if (t !== current) return
        main.postDelayed({ if (t === current && !(onlyWhenLoaded && t.loading)) captureThumb(t) }, delayMs)
    }

    fun recover(t: Tab) {
        if (t === current) engineView?.show(null)
        t.engineTab?.close()
        t.engineTab = null
        if (t === current) switchTo(t)
    }

    fun exitApp() {
        captureThumb(current)
        saveNow()
        store.flush()
        activity.finishAndRemoveTask()
        main.postDelayed({ System.exit(0) }, 300)
    }

    fun startTransfer(export: Boolean, uri: Uri, password: CharArray) {
        captureThumb(current)
        saveNow()
        store.flush()
        activity.startActivity(TransferActivity.intent(activity, export, uri, password))
        password.fill(' ')
        main.postDelayed({ System.exit(0) }, 300)
    }

    fun onStop() {
        if (prefs.bool("closeIncognito", false)) tabs.filter { it.incognito }.forEach(::closeTab)
    }

    // ---------------------------------------------------------------- navigation

    fun navigate(input: String) {
        val s = input.trim()
        val t = current ?: return
        if (s.isEmpty()) return
        val url = when {
            "://" in s || s.startsWith("about:") || s.startsWith("view-source:") || s.startsWith("data:") ||
                s.startsWith("moz-extension:") -> s
            ' ' !in s && '.' in s && !s.endsWith(".") -> "https://$s"
            else -> prefs.searchUrl + Uri.encode(s)
        }
        ensureSession(t).load(url)
        urlFocused = false
    }

    fun load(url: String, newTab: Boolean) {
        val t = current
        if (newTab || t == null || t.incognito) openTab(url, false, null, true) else ensureSession(t).load(url)
        urlFocused = false
    }

    fun goHome() {
        val url = (if (prefs.int("homeMode", 0) == 1) prefs.string("homeUrl", "") else "").ifEmpty { "about:blank" }
        val t = current
        if (t == null) openTab(url, false, null, true) else ensureSession(t).load(url)
    }

    fun handleIntent(intent: Intent?, needTab: Boolean) {
        if (intent?.action == WebNotifications.ACTION_CLICK) {
            WebNotifications.click(intent)
            if (!needTab) return
        }
        val data = intent?.data
        // An explicit intent passes any scheme past the filter; javascript: would run in the open page.
        if (data != null && data.scheme?.lowercase() !in setOf("http", "https")) {
            toast(T.linkUnsupported(scheme = data.scheme))
            if (needTab) newTabAndFocus(false)
            return
        }
        when {
            data != null && intent.action == Intent.ACTION_VIEW -> {
                overlay = Overlay.NONE
                val t = current
                if (prefs.int("external", 0) == 1 && t != null && !t.incognito) ensureSession(t).load(data.toString())
                else openTab(data.toString(), false, null, true)
            }
            data != null && intent.action == null -> openTab(data.toString(), false, null, true)
            needTab -> newTabAndFocus(false)
        }
    }

    fun onBack(): Boolean {
        val t = current
        when {
            dialogs.isNotEmpty() -> dialogs.removeAt(0).onDismiss()
            menuOpen -> menuOpen = false
            overlay != Overlay.NONE -> closeOverlay()
            findVisible -> hideFind()
            urlFocused -> urlFocused = false
            fullscreen && t != null -> t.engineTab?.exitFullscreen()
            t != null && t.canGoBack -> ensureSession(t).goBack()
            t != null && tabs.any { it.id == t.parentId } -> {
                val parent = tabs.first { it.id == t.parentId }
                switchTo(parent)
                closeTab(t)
            }
            else -> return false
        }
        return true
    }

    fun closeOverlay() {
        if (overlay == Overlay.SWITCHER && current == null) newTabAndFocus(false)
        overlay = Overlay.NONE
    }

    fun showSwitcher() {
        captureThumb(current)
        main.postDelayed({
            switcherIncognito = current?.incognito == true
            overlay = Overlay.SWITCHER
            setToolbarHidden(0f)
        }, 60)
    }

    fun showSettings(site: String? = null) {
        settingsSite = site
        settingsOpenCount++
        overlay = Overlay.SETTINGS
        setToolbarHidden(0f)
    }

    fun showLibrary(kind: LibraryKind) {
        libraryKind = kind
        overlay = Overlay.LIBRARY
        setToolbarHidden(0f)
    }

    fun setToolbarHidden(h: Float) {
        toolbarMotion.stop()
        moveToolbar(h)
    }

    fun moveToolbar(h: Float) {
        hidden = h.coerceIn(0f, toolbarHeightPx)
        if (hidden >= toolbarHeightPx) barsHidden = prefs.bool("navBarHide", true)
        else if (hidden <= toolbarHeightPx - navDeadZonePx) barsHidden = false
    }

    fun onScroll(t: Tab, y: Int) {
        if (t !== current || urlFocused) return
        if (lastScrollY < 0) {
            lastScrollY = y
            return
        }
        val dy = y - lastScrollY
        lastScrollY = y
        if (!prefs.bool("toolbarHide", true)) return
        toolbarMotion.onScrolled(dy.toFloat())
        moveToolbar(if (y <= 0) 0f else hidden + dy)
    }

    // Once per start: offer to become the default browser, unless refused for good or postponed.
    fun checkDefaultBrowserOffer() {
        if (defaultBrowserOffer && activity.isDefaultBrowser()) defaultBrowserOffer = false
        if (defaultBrowserChecked) return
        defaultBrowserChecked = true
        if (prefs.bool("defaultBrowserNever", false) || System.currentTimeMillis() < prefs.sp.getLong("defaultBrowserLater", 0)) return
        if (activity.canBeDefaultBrowser() && !activity.isDefaultBrowser()) defaultBrowserOffer = true
    }

    fun makeDefaultBrowser() {
        activity.requestDefaultBrowser { ok ->
            if (ok || activity.isDefaultBrowser()) toast(T.defaultBrowserDone)
            else toast(T.defaultBrowserFailed)
        }
    }

    fun answerDefaultBrowser(yes: Boolean, later: Boolean) {
        defaultBrowserOffer = false
        when {
            yes -> makeDefaultBrowser()
            later -> prefs.edit { putLong("defaultBrowserLater", System.currentTimeMillis() + 3L * 24 * 3600 * 1000) }
            else -> prefs.edit { putBoolean("defaultBrowserNever", true) }
        }
    }

    fun dismissLoginOffer() {
        val p = loginOffer ?: return
        loginOffer = null
        p.dismiss()
    }

    fun pickLogin(index: Int) {
        val p = loginOffer ?: return
        loginOffer = null
        p.select(index)
    }

    // ---------------------------------------------------------------- engine callbacks

    private inner class TabSession(val t: Tab) : TabCallbacks {
        // A new session first loads about:blank, also when it restores a page; like Firefox, that load is ignored.
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
            refreshBookmarked(t)
        }

        override fun errorPage(url: String, error: LoadError): String {
            if (error == LoadError.HTTPS_ONLY) t.httpsOnlyFailed = url
            return ErrorPage.html(url, error, if (prefs.isDark()) DarkPalette else LightPalette)
        }

        override fun onTitleChange(title: String) {
            t.title = title
            if (!t.incognito) {
                val url = t.url
                activity.lifecycleScope.launch { db.io { title(url, title) } }
            }
            scheduleSave()
        }

        override fun onPageStart(url: String) {
            if (skipInitialBlank(url)) return
            if (url != t.httpsOnlyFailed) t.httpsOnlyFailed = null
            t.loading = true
            t.progress = 0
            if (t === current) toolbarMotion.show()
        }

        override fun onPageStop(success: Boolean) {
            if (initialBlank) return
            t.loading = false
            captureSoon(t, 1000, true)
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
            scheduleSave()
        }

        override fun onFirstPaint() = captureSoon(t, 1500, false)

        override fun onScroll(y: Int) {
            this@Browser.onScroll(t, y)
        }

        override fun onFullscreen(full: Boolean) {
            if (t === current) activity.setFullscreen(full)
        }

        override fun onFocusRequest() {
            if (t !== current) main.post { switchTo(t) }
        }

        override fun onCloseRequest() = closeTab(t)

        override fun onCrash() = recover(t)

        override fun onNewTab(url: String, create: (TabOptions, TabCallbacks) -> EngineTab): EngineTab {
            val child = Tab.create(t.incognito)
            child.url = url
            child.parentId = t.id
            child.desktop = prefs.bool("desktopDefault", false)
            tabs.add(tabs.indexOf(t) + 1, child)
            val tab = create(options(child, false), TabSession(child))
            child.engineTab = tab
            main.post { switchTo(child) }
            return tab
        }

        override fun allowJavascript(url: String) = perms.decide(SitePerms.JS, Urls.siteHost(url)) == SitePerms.ALLOW

        override fun onVisit(url: String, title: String?): Boolean {
            if (t.incognito || !Urls.isWeb(url)) return false
            val name = if (t.url == url) t.title else null
            activity.lifecycleScope.launch { db.io { visit(url, name) } }
            return true
        }

        override fun isVisited(urls: List<String>, done: (BooleanArray) -> Unit) {
            activity.lifecycleScope.launch { done(db.io { visited(urls) }) }
        }

        override fun onContextMenu(info: ContextInfo) {
            showContextMenu(t, info)
        }

        override fun onDownload(request: DownloadRequest) = downloads.start(request, prefs.bool("dlAsk", false))

        override fun onPageMeta(pageUrl: String, iconUrl: String?, themeColor: Int?) {
            if (t.incognito) return
            val page = Uri.parse(pageUrl)
            val h = Urls.host(pageUrl)
            icons.setTheme(h, themeColor)
            icons.onPageIcon(h, iconUrl, "${page.scheme}://${page.host}")
        }

        override fun onPrompt(prompt: Prompt) = onPrompt(t, prompt)

        override fun onContentPermission(request: ContentPermissionRequest) = onContentPermission(t, request)

        override fun onMediaPermission(request: MediaPermissionRequest) = onMediaPermission(t, request)

        override fun onAndroidPermission(request: AndroidPermissionRequest) = askAndroidPermission(request)

        override fun onTranslationOffer() {
            if (t !== current || translation == null || !prefs.bool("translateOffer", true)) return
            val from = t.docLang?.let { Urls.langName(it) + " → " } ?: ""
            translateOffer = T.translateOffer(languages = from + Urls.langName(prefs.translateTarget))
            main.postDelayed({ translateOffer = null }, 15000)
        }

        override fun onTranslationState(state: TranslationState) {
            state.pageLanguage?.let { t.docLang = it }
            t.translated = state.translated
            if (state.error != null && t === current) toast(T.translateError(reason = state.error))
        }

        override fun onMedia(event: MediaEvent) {
            activity.pip.onMedia(event, t === current)
            media.onEvent(event, t.title, Urls.host(t.url), icons.get(Urls.host(t.url))) {
                activity.requestNotificationPermission(true)
            }
        }
    }

    private inner class PopupCallbacks(private val onClose: () -> Unit) : TabCallbacks by NoopTabCallbacks() {
        override fun onCloseRequest() = onClose()
    }
}
