package dev.antite.browser.ui

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.Auth
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.FormField
import dev.antite.browser.Overlay
import dev.antite.browser.UiDialog
import dev.antite.browser.data.Option
import dev.antite.browser.data.Options
import dev.antite.browser.l10n.T
import dev.antite.browser.transfer.BackupCipher
import dev.antite.browser.transfer.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class Page(val title: String, val content: @Composable (Nav) -> Unit)

class Nav internal constructor(private val stack: MutableList<Any>) {
    fun push(title: String, content: @Composable (Nav) -> Unit) {
        stack += Page(title, content)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun pop(onRoot: () -> Unit) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onRoot()
    }
}

@Composable
fun SettingsScreen(b: Browser, shown: Boolean) {
    val query = remember { mutableStateOf("") }
    val target = remember { SettingTarget() }
    val root = remember { Page(T.settingsTitle) { MainPage(b, it, query) } }
    val stack = remember { mutableStateListOf<Any>(root) }
    val nav = remember(stack) { Nav(stack) }
    var closes by remember { mutableIntStateOf(0) }
    LaunchedEffect(b.settingsOpenCount) {
        val site = b.settingsSite ?: return@LaunchedEffect
        stack.clear()
        stack += Page(site) { SitePage(b, it, site) }
    }
    // Kept composed between openings, so after closing it goes back to its first page, scrolled to the top.
    LaunchedEffect(shown) {
        if (shown) return@LaunchedEffect
        delay(Motion.pageMs(Motion.SHARED_AXIS).toLong())
        if (stack.size != 1 || stack[0] !== root) {
            stack.clear()
            stack += root
        }
        query.value = ""
        target.clear()
        closes++
    }
    BackHandler(enabled = shown && stack.size > 1) { nav.back() }
    CompositionLocalProvider(LocalPageReset provides closes, LocalSettingTarget provides target) {
        AnimatedContent(stack.size to stack.last() as Page,
            transitionSpec = { Motion.sharedAxisX(forward = targetState.first > initialState.first) }, label = "settings") { (_, page) ->
            page.content(nav)
        }
    }
}

@Composable
internal fun Screen(b: Browser, nav: Nav, title: String, content: LazyListScope.() -> Unit) {
    val target = LocalSettingTarget.current
    val scrollTo = if (target != null && target.page == title) target.index else -1
    CompositionLocalProvider(LocalSettingsPageTitle provides title) {
        PageScaffold(title, { nav.pop { b.overlay = Overlay.NONE } }, scrollTo = scrollTo, content = content)
    }
}

internal fun <T> LazyListScope.choice(b: Browser, nav: Nav, title: String, key: String, options: List<Option<T>>, def: Int,
                                     engine: Boolean, after: () -> Unit = {}) {
    mark(title, null, "choice:$key", options.joinToString(" ") { it.label })
    item(key = "choice:$key") {
        val cur = b.prefs.index(key, options, def)
        Marked("choice:$key") {
            SettingRow(title, options[cur].label, onClick = {
                nav.push(title) { n -> RadioPage(b, n, title, options.map { it.label }, { b.prefs.index(key, options, def) }) { i ->
                    b.prefs.edit { putInt(key, i) }
                    if (engine) b.applyEngineSettings()
                    after()
                } }
            })
        }
    }
}

internal fun LazyListScope.toggle(b: Browser, title: String, summary: String?, key: String, def: Boolean, engine: Boolean) {
    mark(title, summary, "toggle:$key")
    item(key = "toggle:$key") {
        Marked("toggle:$key") {
            SwitchRow(title, summary, b.prefs.bool(key, def)) { v ->
                b.prefs.edit { putBoolean(key, v) }
                if (engine) b.applyEngineSettings()
            }
        }
    }
}

internal fun LazyListScope.nav(nav: Nav, title: String, summary: String?, content: @Composable (Nav) -> Unit) {
    mark(title, summary, "nav:$title")
    item(key = "nav:$title") { Marked("nav:$title") { SettingRow(title, summary, onClick = { nav.push(title, content) }) } }
}

// A row that only runs an action, such as opening a system screen.
internal fun LazyListScope.action(title: String, summary: String?, key: String, onClick: () -> Unit) {
    mark(title, summary, key)
    item(key = key) { Marked(key) { SettingRow(title, summary, onClick = onClick) } }
}

@Composable
internal fun RadioPage(b: Browser, nav: Nav, title: String, options: List<String>, current: () -> Int, onPick: (Int) -> Unit) {
    Screen(b, nav, title) {
        options.forEachIndexed { i, label ->
            item { RadioRow(label, current() == i) { onPick(i) } }
        }
    }
}

@Composable
internal fun MainPage(b: Browser, nav: Nav, query: MutableState<String>) {
    val q = query.value.trim()
    val reset = LocalPageReset.current
    // Built when a search starts: rows show values of the moment, the index only needs titles and places.
    val index = remember(reset, q.isEmpty()) {
        if (q.isEmpty()) emptyList() else SettingsIndex().apply { categories(b, Nav(mutableListOf())) }.entries
    }
    val found = remember(index, q) { findSettings(index, q) }
    val target = LocalSettingTarget.current
    Screen(b, nav, T.settingsTitle) {
        settingsSearchField(query)
        if (q.isEmpty()) categories(b, nav) else settingsSearchResults(b, nav, found, target)
    }
}

internal fun LazyListScope.categories(b: Browser, nav: Nav) {
    page(b, nav, searchHomePage(b), T.settingsCatSearchSummary)
    page(b, nav, appearancePage(b), T.settingsCatAppearanceSummary)
    page(b, nav, tabsPage(b), T.settingsTabsSummary)
    page(b, nav, privacyPage(b), T.settingsPrivacySummary)
    page(b, nav, autofillPage(b), T.settingsCatAutofillSummary)
    nav(nav, T.settingsLanguages, T.settingsLanguagesSummary) { LanguagesPage(b, it) }
    page(b, nav, downloadsPage(b), if (b.prefs.bool("dlAsk", false)) T.settingsDownloadsAsk else T.settingsDownloadsDefault)
    page(b, nav, accessibilityPage(b), T.settingsAccessibilitySummary)
    page(b, nav, systemPage(b), T.settingsCatSystemSummary)
    page(b, nav, aboutPage(b), T.settingsAboutSummary)
}

internal fun searchHomePage(b: Browser) = SettingsPage(T.settingsCatSearch) { nav ->
    choice(b, nav, T.settingsSearchEngine, "engine", Options.engines, 0, false)
    toggle(b, T.privacySuggest, T.privacySuggestSummary, "searchSuggest", true, false)
    nav(nav, T.settingsHome, if (b.prefs.bool("homeButton", false)) homeSummary(b) else T.settingsOff) { HomePage(b, it) }
    section(T.tabNew)
    choice(b, nav, T.tabsFrequent, "ntpFrequent", Options.frequentSites, 3, false)
}

internal fun appearancePage(b: Browser) = SettingsPage(T.settingsCatAppearance) { nav ->
    section(T.appearanceThemeText)
    choice(b, nav, T.settingsTheme, "theme", Options.themes, 2, true)
    choice(b, nav, T.a11yFont, "font", Options.fonts, 2, true)
    interfaceLanguage(b, nav)
    section(T.appearanceAnimations)
    choice(b, nav, T.a11yAnimationStyle, Motion.STYLE_PREF, Options.animationStyles, Motion.CHROME, false) {
        Motion.setStyle(b.prefs.int(Motion.STYLE_PREF, Motion.CHROME))
    }
    choice(b, nav, T.a11yAnimationSpeed, Motion.PREF, Options.animationSpeeds, Motion.DEFAULT_SPEED, false) {
        Motion.setSpeed(b.prefs.int(Motion.PREF, Motion.DEFAULT_SPEED))
    }
    section(T.settingsToolbar)
    toggle(b, T.toolbarHideOnScroll, T.toolbarHideOnScrollSummary, "toolbarHide", true, false)
    toggle(b, T.toolbarHideNavBar, T.toolbarHideNavBarSummary, "navBarHide", true, false)
    choice(b, nav, T.toolbarNavDeadZone, "navDeadZone", Options.navDeadZones, 2, false)
    note(T.toolbarNavDeadZoneNote)
    section(T.settingsTabs)
    choice(b, nav, T.tabsLayout, "tabLayout", Options.tabLayouts, 0, false)
}

internal fun autofillPage(b: Browser) = SettingsPage(T.settingsCatAutofill) { nav ->
    nav(nav, T.settingsPasswords, T.settingsPasswordsSummary) { PasswordsPage(b, it) }
    nav(nav, T.settingsPayment, T.settingsPaymentSummary) { CardsPage(b, it) }
    nav(nav, T.settingsAddresses, T.settingsAddressesSummary) { AddressesPage(b, it) }
}

internal fun downloadsPage(b: Browser) = SettingsPage(T.settingsDownloads) { nav ->
    toggle(b, T.settingsDownloadsAskToggle, T.settingsDownloadsAskSummary, "dlAsk", false, false)
    choice(b, nav, T.dlLayout, "dlLayout", Options.downloadLayouts, 0, false)
    choice(b, nav, T.dlPreview, "dlPreview", Options.downloadPreviews, 0, false)
}

internal fun systemPage(b: Browser) = SettingsPage(T.settingsCatSystem) { nav ->
    mark(T.settingsDefaultBrowser, null, "row:defaultBrowser")
    item(key = "row:defaultBrowser") {
        // Settings stay composed between openings; the default browser may have been changed in Android meanwhile.
        val isDefault = remember(b.settingsOpenCount) { b.activity.isDefaultBrowser() }
        Marked("row:defaultBrowser") {
            SettingRow(T.settingsDefaultBrowser, if (isDefault) T.appName else T.settingsDefaultBrowserOther, onClick = {
                if (isDefault) b.activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                else b.makeDefaultBrowser()
            })
        }
    }
    action(T.settingsNotifications, T.settingsNotificationsSummary, "row:notifications") {
        b.activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, b.activity.packageName))
    }
    page(b, nav, transferPage(b), T.settingsTransferSummary)
}

internal fun aboutPage(b: Browser) = SettingsPage(T.settingsAbout) { nav ->
    nav(nav, T.settingsUpdates, T.settingsUpdatesSummary(state = updatesSummary(b))) { UpdatesPage(b, it) }
    mark("Antite 0.1", null, "row:version")
    item(key = "row:version") {
        SettingRow("Antite 0.1", T.settingsAboutEngine(type = b.engine.info.type, version = b.engine.info.version))
    }
}

internal fun transferPage(b: Browser) = SettingsPage(T.settingsTransfer) {
    action(T.transferExport, T.transferExportSummary, "row:export") {
        Auth.require(b.activity, T.transferExportTitle, { exportAll(b) }, {})
    }
    action(T.transferImport, T.transferImportSummary, "row:import") { importAll(b) }
    note(T.transferNote)
}

internal fun exportAll(b: Browser) {
    val name = "antite-" + java.time.LocalDate.now() + ".antite"
    b.activity.createDocument(name, "application/octet-stream") { uri ->
        if (uri == null) return@createDocument
        var started = false
        b.show(UiDialog.Form(T.transferFilePassword, T.transferFilePasswordNote, listOf(
            FormField(T.promptPassword, kind = FormField.Kind.PASSWORD),
            FormField(T.transferRepeatPassword, kind = FormField.Kind.PASSWORD),
        ), T.transferExportAction, { v ->
            when {
                v[0].isEmpty() -> b.toast(T.transferEnterPassword).let { false }
                v[0] != v[1] -> b.toast(T.transferPasswordsDiffer).let { false }
                else -> {
                    started = true
                    b.startTransfer(true, uri, v[0].toCharArray())
                    true
                }
            }
        }, onDismiss = {
            if (!started) runCatching { DocumentsContract.deleteDocument(b.activity.contentResolver, uri) }
        }))
    }
}

internal fun importAll(b: Browser) = b.activity.openDocument(listOf("application/octet-stream")) { uri ->
    if (uri == null) return@openDocument
    b.show(UiDialog.Form(T.transferPasswordOfFile, null, listOf(FormField(T.promptPassword, kind = FormField.Kind.PASSWORD)), T.actionNext, { v ->
        val password = v[0].toCharArray()
        b.activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.Default) {
                try {
                    b.activity.contentResolver.openInputStream(uri)!!.use { BackupCipher.decrypt(it, password).read() }
                    null
                } catch (e: WrongPasswordException) {
                    T.transferWrongPassword
                } catch (e: Exception) {
                    T.transferNotABackup
                }
            }
            if (error != null) {
                password.fill(' ')
                return@launch b.toast(error)
            }
            b.show(UiDialog.Message(T.transferReplaceTitle,
                T.transferReplaceText,
                buttons = listOf(DialogButton(T.actionCancel) { password.fill(' ') },
                    DialogButton(T.transferImportAction) { b.startTransfer(false, uri, password) }),
                onDismiss = { password.fill(' ') }))
        }
        true
    }))
}

internal fun homeSummary(b: Browser) =
    if (b.prefs.int("homeMode", 0) == 0) T.tabNew else b.prefs.string("homeUrl", "")

@Composable
internal fun HomePage(b: Browser, nav: Nav) {
    fun editUrl(then: () -> Unit = {}) = b.show(UiDialog.Form(T.homeAddressTitle, null,
        listOf(FormField("https://example.com", b.prefs.string("homeUrl", ""), FormField.Kind.URL)),
        onConfirm = { v ->
            var url = v[0].trim()
            if (url.isEmpty()) return@Form false
            if ("://" !in url) url = "https://$url"
            b.prefs.edit { putString("homeUrl", url) }
            then()
            true
        }))
    Screen(b, nav, T.settingsHome) {
        toggle(b, T.homeButton, T.homeButtonSummary, "homeButton", false, false)
        section(T.homeOpen)
        Options.homeModes.forEachIndexed { i, o ->
            item {
                RadioRow(o.label, b.prefs.int("homeMode", 0) == i) {
                    if (i == 1 && b.prefs.string("homeUrl", "").isEmpty()) editUrl { b.prefs.edit { putInt("homeMode", 1) } }
                    else b.prefs.edit { putInt("homeMode", i) }
                }
            }
        }
        if (b.prefs.int("homeMode", 0) == 1) item { SettingRow(T.sourceAddress, b.prefs.string("homeUrl", ""), onClick = { editUrl() }) }
    }
}

internal fun tabsPage(b: Browser) = SettingsPage(T.settingsTabs) { nav ->
    choice(b, nav, T.tabsStartup, "startup", Options.startup, 0, false)
    choice(b, nav, T.tabsExternal, "external", Options.external, 0, false)
    toggle(b, T.tabsCloseIncognito, null, "closeIncognito", false, false)
    toggle(b, T.tabsDesktopDefault, T.tabsDesktopDefaultSummary, "desktopDefault", false, false)
    choice(b, nav, T.tabsLive, "liveTabs", Options.liveTabs, 2, false)
    note(T.tabsLiveNote)
}

internal fun accessibilityPage(b: Browser) = SettingsPage(T.settingsAccessibility) {
    toggle(b, T.a11yForceZoom, T.a11yForceZoomSummary,
        "forceZoom", false, true)
    toggle(b, T.a11yDoubleTapZoom, null, "doubleTapZoom", true, true)
}
