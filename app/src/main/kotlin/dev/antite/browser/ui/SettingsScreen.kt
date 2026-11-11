package dev.antite.browser.ui

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import dev.antite.browser.openUboDashboard
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
    val root = remember { Page(T.settingsTitle) { MainPage(b, it) } }
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
        delay(Motion.ms(Motion.SHARED_AXIS).toLong())
        if (stack.size != 1 || stack[0] !== root) {
            stack.clear()
            stack += root
        }
        closes++
    }
    BackHandler(enabled = shown && stack.size > 1) { nav.back() }
    CompositionLocalProvider(LocalPageReset provides closes) {
        AnimatedContent(stack.size to stack.last() as Page,
            transitionSpec = { Motion.sharedAxisX(forward = targetState.first > initialState.first) }, label = "settings") { (_, page) ->
            page.content(nav)
        }
    }
}

@Composable
internal fun Screen(b: Browser, nav: Nav, title: String, content: LazyListScope.() -> Unit) {
    PageScaffold(title, { nav.pop { b.overlay = Overlay.NONE } }, content = content)
}

internal fun <T> LazyListScope.choice(b: Browser, nav: Nav, title: String, key: String, options: List<Option<T>>, def: Int,
                                     engine: Boolean, after: () -> Unit = {}) = item(key = "choice:$key") {
    val cur = b.prefs.index(key, options, def)
    SettingRow(title, options[cur].label, onClick = {
        nav.push(title) { n -> RadioPage(b, n, title, options.map { it.label }, { b.prefs.index(key, options, def) }) { i ->
            b.prefs.edit { putInt(key, i) }
            if (engine) b.applyEngineSettings()
            after()
        } }
    })
}

internal fun LazyListScope.toggle(b: Browser, title: String, summary: String?, key: String, def: Boolean, engine: Boolean) =
    item(key = "toggle:$key") {
        SwitchRow(title, summary, b.prefs.bool(key, def)) { v ->
            b.prefs.edit { putBoolean(key, v) }
            if (engine) b.applyEngineSettings()
        }
    }

internal fun LazyListScope.nav(nav: Nav, title: String, summary: String?, content: @Composable (Nav) -> Unit) =
    item(key = "nav:$title") { SettingRow(title, summary, onClick = { nav.push(title, content) }) }

@Composable
internal fun RadioPage(b: Browser, nav: Nav, title: String, options: List<String>, current: () -> Int, onPick: (Int) -> Unit) {
    Screen(b, nav, title) {
        options.forEachIndexed { i, label ->
            item { RadioRow(label, current() == i) { onPick(i) } }
        }
    }
}

@Composable
internal fun MainPage(b: Browser, nav: Nav) {
    val p = b.prefs
    Screen(b, nav, T.settingsTitle) {
        section(T.settingsBasics)
        choice(b, nav, T.settingsSearchEngine, "engine", Options.engines, 0, false)
        nav(nav, T.settingsPasswords, T.settingsPasswordsSummary) { PasswordsPage(b, it) }
        nav(nav, T.settingsPayment, T.settingsPaymentSummary) { CardsPage(b, it) }
        nav(nav, T.settingsAddresses, T.settingsAddressesSummary) { AddressesPage(b, it) }
        nav(nav, T.settingsPrivacy, T.settingsPrivacySummary) { PrivacyPage(b, it) }
        item {
            SettingRow(T.settingsNotifications, T.settingsNotificationsSummary, onClick = {
                b.activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, b.activity.packageName))
            })
        }
        choice(b, nav, T.settingsTheme, "theme", Options.themes, 2, true)
        item {
            // Settings stay composed between openings; the default browser may have been changed in Android meanwhile.
            val isDefault = remember(b.settingsOpenCount) { b.activity.isDefaultBrowser() }
            SettingRow(T.settingsDefaultBrowser, if (isDefault) T.appName else T.settingsDefaultBrowserOther, onClick = {
                if (isDefault) b.activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                else b.makeDefaultBrowser()
            })
        }
        section(T.settingsAdvanced)
        nav(nav, T.settingsHome, if (p.bool("homeButton", false)) homeSummary(b) else T.settingsOff) { HomePage(b, it) }
        nav(nav, T.settingsTabs, T.settingsTabsSummary) { TabsPage(b, it) }
        nav(nav, T.settingsToolbar, T.settingsToolbarSummary) { ToolbarPage(b, it) }
        nav(nav, T.settingsAccessibility, T.settingsAccessibilitySummary) { AccessibilityPage(b, it) }
        nav(nav, T.settingsSites, T.settingsSitesSummary) { SiteSettingsPage(b, it) }
        nav(nav, T.settingsLanguages, T.settingsLanguagesSummary) { LanguagesPage(b, it) }
        nav(nav, T.settingsDownloads, if (p.bool("dlAsk", false)) T.settingsDownloadsAsk else T.settingsDownloadsDefault) {
            Screen(b, it, T.settingsDownloads) {
                toggle(b, T.settingsDownloadsAskToggle, T.settingsDownloadsAskSummary, "dlAsk", false, false)
                choice(b, it, T.dlLayout, "dlLayout", Options.downloadLayouts, 0, false)
                choice(b, it, T.dlPreview, "dlPreview", Options.downloadPreviews, 0, false)
            }
        }
        item { SettingRow("uBlock Origin", T.settingsUboSummary, onClick = b::openUboDashboard) }
        nav(nav, T.settingsTransfer, T.settingsTransferSummary) { TransferPage(b, it) }
        section(T.settingsAbout)
        nav(nav, T.settingsUpdates, T.settingsUpdatesSummary(state = updatesSummary(b))) { UpdatesPage(b, it) }
        item { SettingRow("Antite 0.1", T.settingsAboutEngine(type = b.engine.info.type, version = b.engine.info.version)) }
    }
}

@Composable
internal fun TransferPage(b: Browser, nav: Nav) = Screen(b, nav, T.settingsTransfer) {
    item {
        SettingRow(T.transferExport, T.transferExportSummary, onClick = {
            Auth.require(b.activity, T.transferExportTitle, { exportAll(b) }, {})
        })
    }
    item { SettingRow(T.transferImport, T.transferImportSummary, onClick = { importAll(b) }) }
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

@Composable
internal fun TabsPage(b: Browser, nav: Nav) = Screen(b, nav, T.settingsTabs) {
    choice(b, nav, T.tabsLayout, "tabLayout", Options.tabLayouts, 0, false)
    choice(b, nav, T.tabsStartup, "startup", Options.startup, 0, false)
    choice(b, nav, T.tabsExternal, "external", Options.external, 0, false)
    toggle(b, T.tabsCloseIncognito, null, "closeIncognito", false, false)
    toggle(b, T.tabsDesktopDefault, T.tabsDesktopDefaultSummary, "desktopDefault", false, false)
    choice(b, nav, T.tabsLive, "liveTabs", Options.liveTabs, 2, false)
    note(T.tabsLiveNote)
    choice(b, nav, T.tabsFrequent, "ntpFrequent", Options.frequentSites, 3, false)
}

@Composable
internal fun ToolbarPage(b: Browser, nav: Nav) = Screen(b, nav, T.settingsToolbar) {
    toggle(b, T.toolbarHideOnScroll, T.toolbarHideOnScrollSummary, "toolbarHide", true, false)
    toggle(b, T.toolbarHideNavBar, T.toolbarHideNavBarSummary, "navBarHide", true, false)
    choice(b, nav, T.toolbarNavDeadZone, "navDeadZone", Options.navDeadZones, 2, false)
    note(T.toolbarNavDeadZoneNote)
}

@Composable
internal fun AccessibilityPage(b: Browser, nav: Nav) = Screen(b, nav, T.settingsAccessibility) {
    choice(b, nav, T.a11yFont, "font", Options.fonts, 2, true)
    choice(b, nav, T.a11yAnimationSpeed, Motion.PREF, Options.animationSpeeds, Motion.DEFAULT_SPEED, false) {
        Motion.setSpeed(b.prefs.int(Motion.PREF, Motion.DEFAULT_SPEED))
    }
    toggle(b, T.a11yForceZoom, T.a11yForceZoomSummary,
        "forceZoom", false, true)
    toggle(b, T.a11yDoubleTapZoom, null, "doubleTapZoom", true, true)
}
