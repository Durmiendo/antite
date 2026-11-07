package dev.antite.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import dev.antite.browser.data.IconCache
import dev.antite.browser.data.Prefs
import dev.antite.browser.data.SitePerms
import dev.antite.browser.data.Tab
import dev.antite.browser.data.TabStore
import dev.antite.browser.data.Vault
import dev.antite.browser.service.AutofillBridge
import dev.antite.browser.service.Downloads
import dev.antite.browser.service.MediaPlayback
import dev.antite.browser.service.WebNotifications
import dev.antite.engine.AndroidPermissionRequest
import dev.antite.engine.ContentPermissionRequest
import dev.antite.engine.ContextInfo
import dev.antite.engine.DataKind
import dev.antite.engine.DownloadRequest
import dev.antite.engine.Engine
import dev.antite.engine.EngineTab
import dev.antite.engine.EngineView
import dev.antite.engine.MediaEvent
import dev.antite.engine.MediaPermissionRequest
import dev.antite.engine.Prompt
import dev.antite.engine.SecurityInfo
import dev.antite.engine.SitePermission
import dev.antite.engine.TabCallbacks
import dev.antite.engine.TabOptions
import dev.antite.engine.TranslationState
import kotlinx.coroutines.launch

enum class Overlay { NONE, SWITCHER, SETTINGS, LIBRARY }

enum class LibraryKind { HISTORY, BOOKMARKS, CLOSED, DOWNLOADS }

class Browser(val activity: MainActivity, val engine: Engine) {
    companion object {
        private const val MAX_LIVE_SESSIONS = 6
        private const val ALLOW_ONCE = 4
        const val UBO_ID = "uBlock0@raymondhill.net"
        private const val UBO_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    }

    val prefs = Prefs(activity)
    val db = Db.get(activity)
    val vault = Vault.get(activity)
    val perms = SitePerms(activity, prefs, engine)
    val icons = IconCache(activity, engine)
    private val store = TabStore(activity)
    val downloads = Downloads(this)
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

    val dialogs = mutableStateListOf<UiDialog>()
    val messages = mutableStateListOf<String>()

    var hidden by mutableFloatStateOf(0f)
    var fullscreen by mutableStateOf(false)
    var urlFocused by mutableStateOf(false)
    var findVisible by mutableStateOf(false)
    var findResult by mutableStateOf("")
    var translateOffer by mutableStateOf<String?>(null)
    var menuOpen by mutableStateOf(false)
    var suggestQuery by mutableStateOf("")
    var suggestFill by mutableStateOf<String?>(null)
    var ntpRevision by mutableStateOf(0)
    var extensionPopup by mutableStateOf<EngineTab?>(null)

    var engineView: EngineView? = null
    var toolbarHeightPx = 0f
    private var lastScrollY = -1

    init {
        Auth.onUnavailable = ::toast
        perms.wipeLegacyOnce()
        applyEngineSettings()
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

    fun applyEngineSettings() = engine.applySettings(prefs.engineSettings(perms))

    fun toast(text: String) {
        messages += text
    }

    fun show(dialog: UiDialog) {
        dialogs += dialog
    }

    fun confirm(text: String, button: String, action: () -> Unit) = show(
        UiDialog.Message(message = text, buttons = listOf(DialogButton("Отмена"), DialogButton(button, action))),
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
        if (live.size < MAX_LIVE_SESSIONS) return
        for (t in live.take(live.size - MAX_LIVE_SESSIONS + 1)) {
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
            dialogs.isNotEmpty() -> dialogs.removeAt(dialogs.lastIndex).onDismiss()
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
        hidden = h.coerceIn(0f, toolbarHeightPx)
    }

    fun onScroll(t: Tab, y: Int) {
        if (t !== current || urlFocused) return
        if (lastScrollY < 0) {
            lastScrollY = y
            return
        }
        val dy = y - lastScrollY
        lastScrollY = y
        setToolbarHidden(if (y <= 0) 0f else hidden + dy)
    }

    // ---------------------------------------------------------------- page actions

    fun showFind() {
        findVisible = true
        findResult = ""
    }

    fun hideFind() {
        if (!findVisible) return
        findVisible = false
        current?.engineTab?.clearFind()
    }

    fun find(text: String?, backwards: Boolean = false) {
        val tab = current?.engineTab ?: return
        if (text != null && text.isEmpty()) {
            tab.clearFind()
            findResult = ""
            return
        }
        tab.find(text, backwards) { cur, total -> findResult = if (total > 0) "$cur/$total" else "0/0" }
    }

    fun toggleDesktop(t: Tab) {
        t.desktop = !t.desktop
        val s = ensureSession(t)
        s.desktopMode = t.desktop
        s.reload()
        scheduleSave()
    }

    fun toggleDark() {
        prefs.edit { putInt("theme", if (prefs.isDark()) 1 else 2) }
        applyEngineSettings()
    }

    private fun refreshBookmarked(t: Tab) {
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

    fun toggleBookmark(t: Tab) {
        val url = t.url
        val title = t.title
        val add = !t.bookmarked
        t.bookmarked = add
        activity.lifecycleScope.launch { db.io { if (add) addBookmark(url, title) else removeBookmark(url) } }
        toast(if (add) "Добавлено в закладки" else "Удалено из закладок")
    }

    fun bookmarkAllTabs() {
        val pages = tabs.filter { !it.incognito && Urls.isWeb(it.url) }
        val data = pages.map { it.url to it.title }
        activity.lifecycleScope.launch { db.io { data.forEach { addBookmark(it.first, it.second) } } }
        pages.forEach { it.bookmarked = true }
        toast("В закладки добавлено вкладок: ${pages.size}")
    }

    fun translate(t: Tab) {
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
        val tr = engine.translation ?: return toast("Перевод недоступен")
        tr.supported({ fromList, _ ->
            show(UiDialog.Items("Язык страницы", fromList.map { l -> DialogButton(l.name) { runTranslation(s, l.code, to) } }))
        }, { toast("Перевод недоступен") })
    }

    private fun runTranslation(s: EngineTab, from: String, to: String) {
        if (from == to) return toast("Страница уже на языке перевода")
        toast("Перевод… Первый раз скачивается языковая модель")
        s.translate(from, to) { err -> if (err != null) toast("Не удалось перевести: $err") }
    }

    fun clearData(cache: Boolean, siteData: Boolean, permissions: Boolean, history: Boolean) {
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
        engine.storage.clear(kinds) { ok -> toast(if (ok) "Удалено" else "Не удалось удалить данные") }
    }

    fun openExtensionsMenu() {
        val ext = engine.extensions
        val list = ext?.list().orEmpty()
        if (ext == null || list.isEmpty()) return toast("Расширений нет")
        show(UiDialog.Items("Расширения", list.map { e ->
            DialogButton(e.name) {
                if (e.hasAction) ext.clickAction(e.id) else e.optionsUrl?.let { openTab(it, false, current, true) }
            }
        }))
    }

    fun openUboDashboard() {
        val ubo = engine.extensions?.list()?.firstOrNull { it.id == UBO_ID }?.optionsUrl
        if (ubo == null) return toast("uBlock Origin ещё не установлен")
        overlay = Overlay.NONE
        openTab(ubo, false, current, true)
    }

    fun closePopup(tab: EngineTab) {
        if (extensionPopup === tab) extensionPopup = null
        tab.close()
    }

    fun restoreClosed(id: Long, url: String, title: String?, state: String?) {
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

    fun clearDataDialog() = show(UiDialog.Checks(
        "Удалить данные",
        listOf("Кэш", "Cookie и данные сайтов", "Разрешения сайтов", "История просмотров"),
        listOf(true, true, false, false),
        "Удалить",
        { c -> clearData(c[0], c[1], c[2], c[3]) },
    ))

    fun pageInfo() {
        val t = current ?: return
        val host = t.securityHost.ifEmpty { Uri.parse(t.url).host }
        val text = buildString {
            append(t.url).append("\n\n")
            if (t.url.startsWith("https://")) {
                append(if (t.secure) "Подключение защищено" else "Подключение не защищено")
                if (t.issuer.isNotEmpty()) append("\nСертификат: ").append(issuerName(t.issuer))
            } else if (t.url.startsWith("http://")) {
                append("Подключение не защищено (HTTP)")
            }
            if (t.incognito) append("\n\nРежим инкогнито")
        }
        show(UiDialog.Message(
            title = host ?: "Сведения о сайте",
            message = text,
            buttons = buildList {
                add(DialogButton("Копировать URL") { activity.copy(t.url) })
                if (Urls.isWeb(t.url)) add(DialogButton("Настройки сайта") { showSettings(Urls.siteHost(t.url)) })
                add(DialogButton("OK"))
            },
        ))
    }

    private fun issuerName(dn: String): String {
        for (prefix in listOf("O=", "CN=")) {
            dn.split(',').map { it.trim() }.firstOrNull { it.startsWith(prefix) }?.let { return it.removePrefix(prefix) }
        }
        return dn
    }

    fun showContextMenu(t: Tab, info: ContextInfo) {
        val items = mutableListOf<DialogButton>()
        val link = info.linkUrl
        val src = info.srcUrl
        if (link != null) {
            items += DialogButton("Открыть в новой вкладке") {
                openTab(link, t.incognito, t, false)
                toast("Вкладка открыта в фоне")
            }
            if (!t.incognito) items += DialogButton("Открыть в новой вкладке инкогнито") { openTab(link, true, null, true) }
            items += DialogButton("Копировать адрес ссылки") { activity.copy(link) }
            info.linkText?.takeIf { it.isNotEmpty() }?.let { text ->
                items += DialogButton("Копировать текст ссылки") { activity.copy(text) }
            }
            items += DialogButton("Поделиться ссылкой") { activity.share(link, null) }
        }
        if (src != null && info.isImage) {
            items += DialogButton("Открыть изображение в новой вкладке") { openTab(src, t.incognito, t, false) }
            items += DialogButton("Скачать изображение") { ensureSession(t).load(src) }
            items += DialogButton("Копировать адрес изображения") { activity.copy(src) }
        }
        if (items.isNotEmpty()) show(UiDialog.Items(link ?: src, items))
    }

    // ---------------------------------------------------------------- permissions

    private fun askSite(icon: Int, host: String, what: String, answer: (Int) -> Unit) {
        var result = -1
        show(UiDialog.Message(
            title = "$host запрашивает $what",
            icon = icon,
            stacked = true,
            buttons = listOf(
                DialogButton("Разрешить при посещении сайта") { result = SitePerms.ALLOW },
                DialogButton("Разрешить в этот раз") { result = ALLOW_ONCE },
                DialogButton("Не разрешать") { result = SitePerms.BLOCK },
            ),
            onDismiss = { answer(result) },
        ))
    }

    private fun permIcon(c: SitePerms.Cat): Int = when (c) {
        SitePerms.LOCATION -> R.drawable.ic_location_on
        SitePerms.NOTIFICATIONS -> R.drawable.ic_notifications
        SitePerms.LNA -> R.drawable.ic_lan
        SitePerms.XR -> R.drawable.ic_view_in_ar
        SitePerms.STORAGE -> R.drawable.ic_database
        SitePerms.DRM -> R.drawable.ic_shield_lock
        else -> R.drawable.ic_public
    }

    private fun onContentPermission(t: Tab, r: ContentPermissionRequest) {
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
        askSite(permIcon(c), h, c.request ?: "разрешение") { answer ->
            if ((answer == SitePerms.ALLOW || answer == SitePerms.BLOCK) && !t.incognito) {
                perms.setException(c, h, answer)
            } else {
                r.forgetStoredAnswer()
            }
            val allow = answer == SitePerms.ALLOW || answer == ALLOW_ONCE
            if (allow && c == SitePerms.NOTIFICATIONS) activity.requestNotificationPermission(false)
            if (allow) r.allow() else r.deny()
        }
    }

    private fun onMediaPermission(t: Tab, r: MediaPermissionRequest) {
        val h = Urls.siteHost(r.origin)
        val cam = r.camera && perms.decide(SitePerms.CAMERA, h) != SitePerms.BLOCK
        val mic = r.microphone && perms.decide(SitePerms.MIC, h) != SitePerms.BLOCK
        if (!cam && !mic) return r.reject()
        val askCam = cam && perms.decide(SitePerms.CAMERA, h) == SitePerms.ASK
        val askMic = mic && perms.decide(SitePerms.MIC, h) == SitePerms.ASK
        if (!askCam && !askMic) return r.grant(cam, mic)
        val what = when {
            askCam && askMic -> "доступ к камере и микрофону"
            askCam -> "доступ к камере"
            else -> "доступ к микрофону"
        }
        askSite(if (askCam) R.drawable.ic_videocam else R.drawable.ic_mic, h, what) { answer ->
            if ((answer == SitePerms.ALLOW || answer == SitePerms.BLOCK) && !t.incognito) {
                if (askCam) perms.setException(SitePerms.CAMERA, h, answer)
                if (askMic) perms.setException(SitePerms.MIC, h, answer)
            }
            val allow = answer == SitePerms.ALLOW || answer == ALLOW_ONCE
            r.grant(cam && (allow || !askCam), mic && (allow || !askMic))
        }
    }

    private fun askAndroidPermission(r: AndroidPermissionRequest) {
        val need = r.permissions.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) return r.granted()
        activity.requestPermissions(need) { ok -> if (ok) r.granted() else r.denied() }
    }

    private fun onPrompt(t: Tab, p: Prompt) {
        when (p) {
            is Prompt.Popup -> {
                val ok = perms.decide(SitePerms.POPUPS, Urls.siteHost(t.url)) == SitePerms.ALLOW
                if (ok) p.allow() else {
                    p.dismiss()
                    toast("Всплывающее окно заблокировано")
                }
            }
            is Prompt.File -> activity.pickFiles(p.mimeTypes, p.multiple) { uris ->
                if (uris.isNullOrEmpty()) p.dismiss() else p.confirm(uris)
            }
            is Prompt.SaveLogin -> AutofillPrompts.saveLogin(this, p)
            is Prompt.SelectCard -> Auth.require(activity, "Заполнить данные карты", { show(UiDialog.Engine(p)) }, { p.dismiss() })
            is Prompt.SaveCard -> if (prefs.bool("cardsEnabled", true)) show(UiDialog.Engine(p)) else p.dismiss()
            is Prompt.SaveAddress -> if (prefs.bool("addressesEnabled", true)) show(UiDialog.Engine(p)) else p.dismiss()
            else -> show(UiDialog.Engine(p))
        }
    }

    // ---------------------------------------------------------------- engine callbacks

    private inner class TabSession(val t: Tab) : TabCallbacks {
        override fun onLocationChange(url: String) {
            t.url = url
            refreshBookmarked(t)
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
            t.loading = true
            t.progress = 0
            if (t === current) setToolbarHidden(0f)
        }

        override fun onPageStop(success: Boolean) {
            t.loading = false
            captureSoon(t, 1000, true)
        }

        override fun onProgress(percent: Int) {
            t.progress = percent
        }

        override fun onSecurityChange(info: SecurityInfo) {
            t.secure = info.secure
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
            if (t !== current || !prefs.bool("translateOffer", true)) return
            val from = t.docLang?.let { Urls.langName(it) + " → " } ?: ""
            translateOffer = "Перевести страницу? $from" + Urls.langName(prefs.translateTarget)
            main.postDelayed({ translateOffer = null }, 15000)
        }

        override fun onTranslationState(state: TranslationState) {
            state.pageLanguage?.let { t.docLang = it }
            t.translated = state.translated
            if (state.error != null && t === current) toast("Ошибка перевода: ${state.error}")
        }

        override fun onMedia(event: MediaEvent) {
            media.onEvent(event, t.title, Urls.host(t.url), icons.get(Urls.host(t.url))) {
                activity.requestNotificationPermission(true)
            }
        }
    }

    private inner class PopupCallbacks(private val onClose: () -> Unit) : TabCallbacks by NoopTabCallbacks() {
        override fun onCloseRequest() = onClose()
    }
}
