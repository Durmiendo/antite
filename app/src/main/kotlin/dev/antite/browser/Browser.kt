package dev.antite.browser

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.data.Categories
import dev.antite.browser.data.Db
import dev.antite.browser.data.Entry
import dev.antite.browser.data.HistoryStore
import dev.antite.browser.data.Secure
import dev.antite.browser.engine.EngineUpdates
import dev.antite.browser.data.IconCache
import dev.antite.browser.data.MediaVault
import dev.antite.browser.data.Options
import dev.antite.browser.data.Prefs
import dev.antite.browser.data.Site
import dev.antite.browser.data.SitePerms
import dev.antite.browser.data.Tab
import dev.antite.browser.data.Containers
import dev.antite.browser.data.TabCategory
import dev.antite.browser.data.TabContainer
import dev.antite.browser.data.TabStore
import dev.antite.browser.data.Vault
import dev.antite.browser.data.nextAfterClose
import dev.antite.browser.l10n.T
import dev.antite.browser.service.AutofillBridge
import dev.antite.browser.service.Downloads
import dev.antite.browser.service.MediaPlayback
import dev.antite.browser.service.WebNotifications
import dev.antite.browser.transfer.TransferActivity
import dev.antite.browser.ui.Motion
import dev.antite.engine.Engine
import dev.antite.engine.EngineTab
import dev.antite.engine.EngineView
import dev.antite.engine.Prompt
import dev.antite.engine.TabCallbacks
import dev.antite.engine.TabOptions
import dev.antite.keys.CategoryKeys
import dev.antite.keys.KeyRing
import java.io.File
import java.io.IOException
import kotlinx.coroutines.launch

enum class Overlay { NONE, SWITCHER, SETTINGS, LIBRARY, VAULT }

enum class LibraryKind { HISTORY, BOOKMARKS, CLOSED, DOWNLOADS }

class Browser(val activity: MainActivity, val engine: Engine) {
    companion object {
        internal const val ALLOW_ONCE = 4
        private const val CRASH_LOOP_MS = 60_000L
        private const val RESTORE_COVER_MS = 4000L
        const val UBO_ID = "uBlock0@raymondhill.net"

        // Page translation is off until it works well (user, 10.11.2026); this one switch brings it back.
        const val TRANSLATION_ENABLED = false

        // Bookmarks are off: the user does not use them (17.11.2026); the data stays in the database.
        const val BOOKMARKS_ENABLED = false
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
    internal val media = MediaPlayback(activity)
    internal val main = Handler(Looper.getMainLooper())
    private val saveTask = Runnable { saveNow() }

    val tabs = mutableStateListOf<Tab>()
    var current by mutableStateOf<Tab?>(null)
        private set
    val mediaVault = MediaVault.get(activity)
    val containers = Containers(prefs)

    var overlay by mutableStateOf(Overlay.NONE)
    var libraryKind by mutableStateOf(LibraryKind.HISTORY)
    var settingsSite by mutableStateOf<String?>(null)
    // A switcher page key: settings open on that category's page, or on tabs or incognito for the fixed pages.
    var settingsCategory by mutableStateOf<String?>(null)
    var settingsOpenCount by mutableStateOf(0)
    val categories = Categories(activity, prefs)
    // The switcher's page: Categories.ALL, Categories.INCOGNITO or a category id.
    var switcherPage by mutableStateOf(Categories.ALL)
    val locks = Locks(object : Locks.Settings {
        override val incognitoLock get() = prefs.bool("incognitoLock", false)
        override fun categoryLock(id: String) = categories.byId(id)?.locked == true
        override val vaultLockOnExit get() = prefs.int("vaultLock", 0) == 0
        override val vaultLockMinutes get() = when (prefs.int("vaultLock", 0)) {
            1 -> 5
            Options.vaultLocks.size -> prefs.custom("vaultLock", Options.vaultLocks.size)?.let(Options.customVaultLock)
            else -> null
        }
    })
    val unlockedCategories get() = locks.unlockedCategories

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
    val defaultBrowser = DefaultBrowserOffer(activity, prefs, ::toast)
    val vaultAccess = VaultAccess(this)
    val categoryKeys = CategoryKeys(File(activity.filesDir, "history/keys"), { ring.withKey(KeyRing.HISTORY, it) }, { Secure.cipher(activity) })
    val history = HistoryStore(File(activity.filesDir, "history"), categoryKeys::key, { Secure.cipher(activity) })
    // The history page shows this: Categories.ALL or a category id.
    var historyScope by mutableStateOf(Categories.ALL)
    // The tab whose last picture is drawn over the page while it loads again after being lost in the background.
    var restoreCover by mutableStateOf<Tab?>(null)
        internal set
    private var restoreOnReturn: Tab? = null
    // When the update source's index ran out, seconds; 0 while it is alive or the warning is postponed.
    var indexExpired by mutableStateOf(0L)
    var loginOffer by mutableStateOf<Prompt.SelectLogin?>(null)
    var menuOpen by mutableStateOf(false)
    var suggestQuery by mutableStateOf("")
    var suggestFill by mutableStateOf<String?>(null)
    var ntpRevision by mutableStateOf(0)
    // Kept here, so the new tab page shows its tiles from its first frame and only refreshes them.
    var ntpPins by mutableStateOf(emptyList<Entry>())
    var ntpFrequent by mutableStateOf(emptyList<Site>())
    var extensionPopup by mutableStateOf<EngineTab?>(null)

    // The scroll of the current tab's page: pull to refresh may start only when the page is at the top.
    var pageScrollY by mutableIntStateOf(0)
    // The tab whose reload pull to refresh started; the spinner is shown over it until the page finishes loading.
    // Page stops are counted because a failed load starts and stops within one frame, so `loading` alone never
    // changes value.
    var pullRefreshTab by mutableStateOf<Tab?>(null)
    var pageStops by mutableIntStateOf(0)

    var engineView: EngineView? = null
    var toolbarHeightPx = 0f
    var navDeadZonePx = 0f

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
        engine.setAutofillStore(AutofillBridge(vault, activity.lifecycleScope) { then, cancelled -> unlockSecrets(T.keysUnlock, then, cancelled) })
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
        val count = prefs.pick("ntpFrequent", Options.frequentSites, 3, Options.customFrequent)
        val category = current?.category
        val (pins, frequent) = db.io {
            val list = pins()
            list to historyOr(emptyList()) { frequent(category, hidden + list.map { Urls.host(it.url) }, count) }
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

    // History works only while the key ring can give its key: with a password mode, after it is unlocked. Until then
    // nothing is recorded or shown, instead of failing.
    fun <R> historyOr(fallback: R, block: HistoryStore.() -> R): R = try {
        history.block()
    } catch (e: IOException) {
        Log.w("Antite", "history unavailable", e)
        fallback
    }

    // Their logs and keys go: what was recorded cannot be read again, even from a copy of the files. Needs no key.
    fun eraseHistory(categories: Collection<String?>) = categories.forEach {
        history.forget(it)
        categoryKeys.forget(HistoryStore.nameOf(it))
    }

    // What the history page lists for its scope: a locked category lists nothing until it is opened.
    fun historyCategories(): List<String?> = when (val s = historyScope) {
        Categories.ALL -> openCategories()
        else -> when {
            categories.byId(s) == null -> openCategories()
            categoryLocked(s) -> emptyList()
            else -> listOf(s)
        }
    }

    // Categories whose history "Все" shows: those open now, and the tabs outside any category.
    fun openCategories(): List<String?> = listOf<String?>(null) + categories.list.filter { !categoryLocked(it.id) }.map { it.id }

    // Before this store, history was one SQLite table: it goes into every category, as the user decided, then the table
    // is emptied. Tried again on the next start while the key ring is locked.
    private suspend fun moveOldHistory() {
        if (prefs.bool("historyMoved", false)) return
        val moved = db.io {
            val pages = oldHistory()
            historyOr(false) {
                (listOf<String?>(null) + categories.list.map { it.id }).forEach { import(it, pages) }
                true
            }.also { if (it) dropOldHistory() }
        }
        if (moved) prefs.edit { putBoolean("historyMoved", true) }
    }

    fun restore() {
        activity.lifecycleScope.launch { moveOldHistory() }
        val loaded = store.load()
        tabs.addAll(loaded.tabs)
        val sel = tabs.firstOrNull { it.id == loaded.selected } ?: tabs.lastOrNull()
        if (sel != null) switchTo(sel)
    }

    // Tabs behind a lock are not counted anywhere: the count would tell how many there are.
    fun incognitoCount(incognito: Boolean) = tabs.count { it.incognito == incognito && !tabLocked(it) }

    fun newTabAndFocus(incognito: Boolean, category: String? = if (incognito) null else current?.category) {
        openTab("about:blank", incognito, null, true, category)
        urlFocused = true
    }

    // Without a word about the container, a tab opened from a page stays in that page's container and a new tab of a
    // category takes the category's one.
    fun openTab(url: String, incognito: Boolean, parent: Tab?, select: Boolean,
                category: String? = parent?.category?.takeIf { !incognito },
                container: String? = if (parent != null) parent.container else categories.byId(category)?.container): Tab {
        val t = Tab.create(incognito)
        t.url = url
        t.category = category?.takeIf { !incognito && categories.byId(it) != null }
        t.container = container?.takeIf { !incognito && containers.byId(it) != null }
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

    internal fun options(t: Tab, withState: Boolean) = TabOptions(
        privateMode = t.incognito,
        desktopMode = t.desktop,
        javascript = perms.decide(SitePerms.JS, Urls.siteHost(t.url)) == SitePerms.ALLOW,
        state = if (withState) t.state else null,
        url = t.url,
        container = t.container?.takeIf { !t.incognito },
    )

    fun ensureSession(t: Tab): EngineTab =
        t.engineTab ?: engine.createTab(options(t, true), TabSession(this, t)).also { t.engineTab = it }

    fun switchTo(t: Tab) {
        val prev = current
        if (prev != null && prev !== t) prev.engineTab?.apply {
            setActive(false)
            setPriority(false)
        }
        urlFocused = false
        if (restoreCover !== t) restoreCover = null
        current = t
        t.lastUsed = System.currentTimeMillis()
        val s = ensureSession(t)
        engineView?.show(s)
        s.setActive(true)
        s.setPriority(true)
        lastScrollY = -1
        pageScrollY = 0
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
        val max = prefs.pick("liveTabs", Options.liveTabs, 2, Options.customLiveTabs)
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
            val next = nextAfterClose(tabs, t, i)
            when {
                next != null -> switchTo(next)
                overlay == Overlay.SWITCHER -> {}
                else -> newTabAndFocus(false)
            }
        }
        scheduleSave()
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
        // Nothing of the page is on screen while it is lost or still under its old picture.
        if (t.engineTab == null || restoreCover === t) return
        engineView?.capture { b ->
            if (b == null) return@capture
            t.thumb = Bitmap.createScaledBitmap(b, maxOf(1, b.width / 3), maxOf(1, b.height / 3), true)
            store.saveThumb(t)
        }
    }

    internal fun captureSoon(t: Tab, delayMs: Long, onlyWhenLoaded: Boolean) {
        if (t !== current) return
        main.postDelayed({ if (t === current && !(onlyWhenLoaded && t.loading)) captureThumb(t) }, delayMs)
    }

    // A page that brings its process down again within a minute is not reloaded by itself, or it would loop; the tab
    // stays empty until the user reloads it. Only crashes on screen count: the engine reports the system killing a
    // process in the background the same way.
    // A page lost while the browser is not visible is not loaded there, where the system would kill it again: it
    // comes back when the user does, under its last picture.
    fun recover(t: Tab) {
        val now = SystemClock.elapsedRealtime()
        val state = activity.lifecycle.currentState
        val onScreen = state.isAtLeast(Lifecycle.State.RESUMED)
        val again = onScreen && t.lastCrashAt != 0L && now - t.lastCrashAt < CRASH_LOOP_MS
        if (onScreen) t.lastCrashAt = now
        if (t === current) engineView?.show(null)
        t.engineTab?.close()
        t.engineTab = null
        if (t !== current) return
        if (!state.isAtLeast(Lifecycle.State.STARTED)) {
            restoreOnReturn = t
            if (t.thumb != null) restoreCover = t
            return
        }
        if (again) toast(T.pageCrashedAgain) else switchTo(t)
    }

    fun restoreLostPage() {
        val t = restoreOnReturn ?: return
        restoreOnReturn = null
        if (t !== current || t.engineTab != null) {
            restoreCover = null
            return
        }
        switchTo(t)
        // The first paint takes the picture away; a page that never paints must not stay hidden behind it.
        main.postDelayed({ if (restoreCover === t) restoreCover = null }, RESTORE_COVER_MS)
    }

    fun reload(t: Tab) {
        if (t.engineTab == null && t === current) switchTo(t) else ensureSession(t).reload()
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
        Uploads.wipe(activity)
        if (locks.left()) lockKeys()
    }

    val incognitoLocked: Boolean get() = locks.incognitoLocked

    fun categoryLocked(id: String?): Boolean = locks.categoryLocked(id)

    fun tabLocked(t: Tab): Boolean = locks.tabLocked(t.incognito, t.category)

    fun unlockCategory(c: TabCategory) {
        if (!categoryLocked(c.id)) return
        Auth.require(activity, T.categoryUnlockReason(name = c.name), { unlockedCategories += c.id }, {})
    }

    fun unlockIncognito(then: () -> Unit = {}, cancelled: () -> Unit = {}) {
        if (!incognitoLocked) return then()
        Auth.require(activity, T.incognitoUnlockReason, {
            locks.incognitoUnlocked = true
            then()
        }, cancelled)
    }

    // A pin opens in its category: the blank tab showing the pin takes the category, otherwise a new tab is opened in it.
    fun openPin(url: String, category: String?, newTab: Boolean) {
        val t = current
        val c = category?.takeIf { categories.byId(it) != null }
        if (!newTab && t != null && !t.incognito && Urls.isBlank(t.url)) {
            t.category = c
            ensureSession(t).load(url)
            urlFocused = false
            scheduleSave()
        } else {
            openTab(url, false, null, true, c)
            urlFocused = false
        }
    }

    fun moveToCategory(t: Tab, category: String?) {
        if (t.incognito) return
        t.category = category
        scheduleSave()
    }

    // The page of the current tab in a new tab of another container (null: of none).
    fun openInContainer(c: TabContainer?) {
        val t = current ?: return
        openTab(t.url, false, t, true, t.category, c?.id)
    }

    // Its tabs are closed and its site data is wiped: nothing of the container is left.
    fun deleteContainer(c: TabContainer) {
        tabs.filter { it.container == c.id }.forEach(::closeTab)
        categories.list.filter { it.container == c.id }.forEach { it.container = null }
        categories.save()
        containers.delete(c)
        engine.storage.clearContainer(c.id) {}
    }

    fun deleteCategory(c: TabCategory) {
        tabs.filter { it.category == c.id }.forEach { it.category = null }
        categories.delete(c)
        if (switcherPage == c.id) switcherPage = Categories.ALL
        scheduleSave()
        activity.lifecycleScope.launch {
            db.io {
                clearPinCategory(c.id)
                eraseHistory(listOf(c.id))
            }
            ntpRevision++
        }
    }

    // ---------------------------------------------------------------- navigation

    fun navigate(input: String) {
        val s = input.trim()
        val t = current ?: return
        if (s.isEmpty()) return
        val url = when {
            "://" in s || s.startsWith("about:") || s.startsWith("view-source:") || s.startsWith("data:") ||
                s.startsWith("moz-extension:") -> if (Urls.canOpenTyped(s)) s else prefs.search(s)
            ' ' !in s && '.' in s && !s.endsWith(".") -> "https://$s"
            else -> prefs.search(s)
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
        when (val r = routeIntent(intent?.action, intent?.data?.toString(), intent?.data?.scheme, needTab)) {
            is IntentRoute.Share -> {
                vaultAccess.takeShared(intent!!)
                if (r.needTab) newTabAndFocus(false)
            }
            is IntentRoute.Unsupported -> {
                toast(T.linkUnsupported(scheme = r.scheme))
                if (r.needTab) newTabAndFocus(false)
            }
            is IntentRoute.Open -> {
                if (r.view) overlay = Overlay.NONE
                val t = current
                if (r.view && prefs.int("external", 0) == 1 && t != null && !t.incognito) ensureSession(t).load(r.url)
                else openTab(r.url, false, null, true)
            }
            IntentRoute.NewTab -> newTabAndFocus(false)
            IntentRoute.Nothing -> {}
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
            val c = current
            switcherPage = when {
                c?.incognito == true -> Categories.INCOGNITO
                c?.category != null && categories.byId(c.category) != null -> c.category!!
                else -> Categories.ALL
            }
            overlay = Overlay.SWITCHER
            setToolbarHidden(0f)
        }, 60)
    }

    fun showSettings(site: String? = null, category: String? = null) {
        settingsSite = site
        settingsCategory = category
        settingsOpenCount++
        overlay = Overlay.SETTINGS
        setToolbarHidden(0f)
    }

    fun showLibrary(kind: LibraryKind) {
        libraryKind = kind
        if (kind == LibraryKind.HISTORY) historyScope = current?.takeIf { !it.incognito }?.category ?: Categories.ALL
        overlay = Overlay.LIBRARY
        setToolbarHidden(0f)
    }

    fun showVault() {
        vaultAccess.relock()
        overlay = Overlay.VAULT
        setToolbarHidden(0f)
    }

    fun setToolbarHidden(h: Float) {
        toolbarMotion.stop()
        moveToolbar(h)
    }

    fun moveToolbar(h: Float) {
        hidden = h.coerceIn(0f, toolbarHeightPx)
        barsHidden = navBarHidden(hidden, toolbarHeightPx, navDeadZonePx, barsHidden, prefs.bool("navBarHide", true))
    }

    fun onScroll(t: Tab, y: Int) {
        if (t !== current) return
        pageScrollY = y
        if (urlFocused) return
        if (lastScrollY < 0) {
            lastScrollY = y
            return
        }
        val dy = y - lastScrollY
        lastScrollY = y
        if (!prefs.bool("toolbarHide", true)) return
        val byScroll = toolbarMotion.onScrolled(dy.toFloat())
        if (y <= 0) moveToolbar(0f) else if (byScroll) moveToolbar(hidden + dy)
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

    private inner class PopupCallbacks(private val onClose: () -> Unit) : TabCallbacks by NoopTabCallbacks() {
        override fun onCloseRequest() = onClose()
    }
}
