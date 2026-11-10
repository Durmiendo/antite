package dev.antite.browser.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.Auth
import dev.antite.browser.Browser
import dev.antite.browser.BuildConfig
import dev.antite.browser.DialogButton
import dev.antite.browser.FormField
import dev.antite.browser.Overlay
import dev.antite.browser.UiDialog
import dev.antite.browser.Urls
import dev.antite.browser.data.Option
import dev.antite.browser.data.Options
import dev.antite.browser.data.SavedLogin
import dev.antite.browser.data.SitePerms
import dev.antite.browser.engine.EngineUpdates
import dev.antite.browser.engine.Engines
import dev.antite.browser.engine.EngineStore
import dev.antite.browser.engine.SourceException
import dev.antite.browser.engine.ShellInstaller
import dev.antite.browser.engine.Sources
import dev.antite.browser.l10n.L10n
import dev.antite.browser.l10n.T
import dev.antite.browser.transfer.BackupCipher
import dev.antite.browser.transfer.WrongPasswordException
import dev.antite.engine.Address
import dev.antite.engine.BackgroundService
import dev.antite.engine.Card
import dev.antite.engine.Language
import dev.antite.engine.LanguageModel
import dev.antite.engine.PermissionValue
import dev.antite.engine.SitePermission
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private class Page(val title: String, val content: @Composable (Nav) -> Unit)

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
fun SettingsScreen(b: Browser) {
    val stack = remember(b.settingsOpenCount) {
        val site = b.settingsSite
        mutableStateListOf<Any>(
            if (site != null) Page(site) { SitePage(b, it, site) } else Page(T.settingsTitle) { MainPage(b, it) },
        )
    }
    val nav = remember(stack) { Nav(stack) }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val page = stack.last() as Page
    page.content(nav)
}

@Composable
private fun Screen(b: Browser, nav: Nav, title: String, content: LazyListScope.() -> Unit) {
    PageScaffold(title, { nav.pop { b.overlay = Overlay.NONE } }, content = content)
}

private fun <T> LazyListScope.choice(b: Browser, nav: Nav, title: String, key: String, options: List<Option<T>>, def: Int,
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

private fun LazyListScope.toggle(b: Browser, title: String, summary: String?, key: String, def: Boolean, engine: Boolean) =
    item(key = "toggle:$key") {
        SwitchRow(title, summary, b.prefs.bool(key, def)) { v ->
            b.prefs.edit { putBoolean(key, v) }
            if (engine) b.applyEngineSettings()
        }
    }

private fun LazyListScope.nav(nav: Nav, title: String, summary: String?, content: @Composable (Nav) -> Unit) =
    item(key = "nav:$title") { SettingRow(title, summary, onClick = { nav.push(title, content) }) }

@Composable
private fun RadioPage(b: Browser, nav: Nav, title: String, options: List<String>, current: () -> Int, onPick: (Int) -> Unit) {
    Screen(b, nav, title) {
        options.forEachIndexed { i, label ->
            item { RadioRow(label, current() == i) { onPick(i) } }
        }
    }
}

@Composable
private fun MainPage(b: Browser, nav: Nav) {
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
            val isDefault = b.activity.isDefaultBrowser()
            SettingRow(T.settingsDefaultBrowser, if (isDefault) T.appName else T.settingsDefaultBrowserOther, onClick = {
                if (isDefault) b.activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                else b.makeDefaultBrowser()
            })
        }
        section(T.settingsAdvanced)
        nav(nav, T.settingsHome, if (p.bool("homeButton", false)) homeSummary(b) else T.settingsOff) { HomePage(b, it) }
        nav(nav, T.settingsTabs, T.settingsTabsSummary) { TabsPage(b, it) }
        nav(nav, T.settingsAccessibility, T.settingsAccessibilitySummary) { AccessibilityPage(b, it) }
        nav(nav, T.settingsSites, T.settingsSitesSummary) { SiteSettingsPage(b, it) }
        nav(nav, T.settingsLanguages, T.settingsLanguagesSummary) { LanguagesPage(b, it) }
        nav(nav, T.settingsDownloads, if (p.bool("dlAsk", false)) T.settingsDownloadsAsk else T.settingsDownloadsDefault) {
            Screen(b, it, T.settingsDownloads) {
                toggle(b, T.settingsDownloadsAskToggle, T.settingsDownloadsAskSummary, "dlAsk", false, false)
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
private fun TransferPage(b: Browser, nav: Nav) = Screen(b, nav, T.settingsTransfer) {
    item {
        SettingRow(T.transferExport, T.transferExportSummary, onClick = {
            Auth.require(b.activity, T.transferExportTitle, { exportAll(b) }, {})
        })
    }
    item { SettingRow(T.transferImport, T.transferImportSummary, onClick = { importAll(b) }) }
    note(T.transferNote)
}

private fun exportAll(b: Browser) {
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

private fun importAll(b: Browser) = b.activity.openDocument(listOf("application/octet-stream")) { uri ->
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

private fun homeSummary(b: Browser) =
    if (b.prefs.int("homeMode", 0) == 0) T.tabNew else b.prefs.string("homeUrl", "")

@Composable
private fun PrivacyPage(b: Browser, nav: Nav) {
    val doh = b.prefs.index("doh", Options.doh, 0)
    Screen(b, nav, T.settingsPrivacy) {
        item { SettingRow(T.menuClearData, T.privacyClearSummary, onClick = b::clearDataDialog) }
        section(T.privacySecurity)
        toggle(b, T.privacyHttpsOnly,
            T.privacyHttpsOnlySummary, "httpsOnly", false, true)
        b.engine.backgroundServices.firstOrNull { it.id == BackgroundService.SAFE_BROWSING }?.let { sb ->
            toggle(b, T.privacySafeBrowsing,
                T.privacySafeBrowsingSummary,
                b.prefs.serviceKey(sb.id), sb.defaultEnabled, true)
        }
        nav(nav, T.privacySecureDns, Options.doh[doh].label) { DnsPage(b, it) }
        section(T.privacyPrivacy)
        choice(b, nav, T.privacyTracking, "tracking", Options.tracking, 0, true)
        choice(b, nav, T.privacyCookies, "cookies", Options.cookies, 0, true)
        choice(b, nav, T.privacyFingerprint, "fingerprint", Options.fingerprint, 0, true)
        if (b.prefs.int("fingerprint", 0) == 2) {
            note(T.privacyFingerprintMaximumNote)
        }
        choice(b, nav, "WebRTC", "webrtc", Options.webRtc, 1, true)
        toggle(b, T.privacyDnt, null, "dnt", false, true)
        toggle(b, "Global Privacy Control", T.privacyGpcSummary, "gpc", true, true)
        toggle(b, T.privacyStrip, T.privacyStripSummary, "strip", true, true)
        toggle(b, T.privacyPreload, T.privacyPreloadSummary,
            "preload", false, true)
        toggle(b, T.privacySuggest, T.privacySuggestSummary,
            "searchSuggest", true, false)
        nav(nav, T.privacyNetwork, T.privacyNetworkSummary) { NetworkPage(b, it) }
    }
}

private fun serviceSummary(s: BackgroundService, on: Boolean) = buildString {
    append(s.summary).append("\n").append(T.networkDestination(hosts = s.hosts.joinToString(", ")))
    if (!on && s.offEffect != null) append("\n").append(T.networkWhenOff(effect = s.offEffect))
}

@Composable
private fun NetworkPage(b: Browser, nav: Nav) = Screen(b, nav, T.privacyNetwork) {
    note(T.networkNote)
    section("Antite")
    nav(nav, T.settingsUpdates, T.networkUpdates(interval = updatesSummary(b).replaceFirstChar { it.uppercase() }) + "\n" +
        T.networkDestination(hosts = Sources.updateSource(b.activity).let { Uri.parse(it.url).host ?: it.url })) {
        UpdatesPage(b, it)
    }
    toggle(b, T.privacySuggest,
        T.networkSuggestSummary + "\n" + T.networkDestination(hosts = Uri.parse(b.prefs.suggestUrl).host), "searchSuggest", true, false)
    toggle(b, T.networkIcons, T.networkIconsSummary + "\n" + T.networkIconsDestination, "siteIcons", true, false)
    item(key = "dns") {
        val doh = Options.doh[b.prefs.index("doh", Options.doh, 0)].value
        SettingRow(T.privacySecureDns,
            if (doh == null) T.networkDnsOff else T.networkDnsAll + "\n" + T.networkDestination(hosts = Uri.parse(doh).host),
            onClick = { nav.push(T.privacySecureDns) { DnsPage(b, it) } })
    }
    section(T.networkEngine(type = b.engine.info.type))
    b.engine.backgroundServices.forEach { s ->
        item(key = "bg:${s.id}") {
            val on = b.prefs.serviceEnabled(s)
            SwitchRow(s.title, serviceSummary(s, on), on) { v ->
                b.prefs.edit { putBoolean(b.prefs.serviceKey(s.id), v) }
                b.applyEngineSettings()
            }
        }
    }
    section(T.networkExtensions)
    item {
        SettingRow("uBlock Origin",
            T.networkUboSummary,
            onClick = b::openUboDashboard)
    }
    section(T.networkNotSent)
    note(T.networkNotSentNote)
}

private class UpdateInfo(
    val active: String?, val activeFrom: String?,
    val pending: String?, val pendingFrom: String?,
    val previous: String?, val previousFrom: String?,
    val lastCheck: Long, val lastError: String?, val available: String?, val shell: Int,
)

private fun from(origin: String?) = if (origin == null) "" else T.updatesOrigin(origin = origin)

@Composable
private fun UpdatesPage(b: Browser, nav: Nav) {
    val ctx = b.activity
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var shellBusy by remember { mutableStateOf<String?>(null) }
    val info by produceState<UpdateInfo?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            val store = Engines.store(ctx)
            val st = EngineUpdates.state(ctx)
            val a = store.active()
            val p = store.pending()
            val prev = store.previous()
            UpdateInfo(
                a?.let(store::version), a?.let { EngineUpdates.origin(ctx, it) },
                p?.let(store::version), p?.let { EngineUpdates.origin(ctx, it) },
                prev?.let(store::version), prev?.let { EngineUpdates.origin(ctx, it) },
                st.getLong("lastCheck", 0), st.getString("lastError", null), st.getString("available", null),
                st.getInt("shellAvailable", 0),
            )
        }
    }
    fun checkNow() = ctx.lifecycleScope.launch {
        busy = T.updatesChecking
        val r = withContext(Dispatchers.IO) { runCatching { EngineUpdates.check(ctx) } }
        busy = null
        revision++
        val offer = r.getOrNull()?.engine
        when {
            r.isFailure -> b.toast(T.updatesCheckFailed)
            offer == null -> if (r.getOrNull()?.shell == null) b.toast(T.updatesNone)
            else -> b.confirm(T.updatesEngineConfirm(version = offer.entry.version, source = offer.source.name,
                size = (offer.size + 512 * 1024) / (1024 * 1024)), T.actionDownload) {
                busy = T.updatesDownloading
                b.engineNews.install(offer, { busy = T.updatesDownloadingPercent(percent = it) }) {
                    busy = null
                    revision++
                }
            }
        }
    }
    fun rollBack(i: UpdateInfo) = b.confirm(T.updatesRollbackConfirm(previous = i.previous, active = i.active), T.updatesRollbackAction) {
        ctx.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val store = Engines.store(ctx)
                store.active()?.let(store::reject)
                store.previous()?.let(store::setPending)
            }
            b.engineNews.restart()
        }
    }
    Screen(b, nav, T.settingsUpdates) {
        val i = info ?: return@Screen
        if (i.pending != null) {
            item(key = "restart") {
                SettingRow(T.updatesRestart, T.updatesRestartSummary(version = i.pending),
                    onClick = b.engineNews::restart)
            }
        }
        if (i.shell > BuildConfig.VERSION_CODE) {
            item(key = "shell") {
                SettingRow(T.updatesShell, shellBusy ?: T.updatesShellSummary(version = i.shell),
                    onClick = {
                        if (shellBusy == null) {
                            shellBusy = T.updatesDownloading
                            b.engineNews.updateShell({ shellBusy = T.updatesDownloadingPercent(percent = it) }) { shellBusy = null }
                        }
                    })
            }
        }
        item(key = "check") {
            val last = when {
                i.lastCheck == 0L -> T.updatesNeverChecked
                else -> T.updatesLastCheck(time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT).format(java.util.Date(i.lastCheck))) + when {
                    i.available != null -> T.updatesLastAvailable(version = i.available)
                    i.shell > BuildConfig.VERSION_CODE -> T.updatesLastShell
                    i.lastError != null -> T.updatesLastError(reason = i.lastError)
                    else -> T.updatesLastNone
                }
            }
            SettingRow(T.updatesCheckNow, busy ?: last, onClick = { if (busy == null) checkNow() })
        }
        section(T.updatesHow)
        item(key = "auto") {
            SwitchRow(T.updatesAuto, null, b.prefs.bool(EngineUpdates.AUTO, true)) { v ->
                b.prefs.edit { putBoolean(EngineUpdates.AUTO, v) }
                EngineUpdates.schedule(ctx)
            }
        }
        if (b.prefs.bool(EngineUpdates.AUTO, true)) {
            choice(b, nav, T.updatesHowOften, EngineUpdates.INTERVAL, Options.updateInterval, EngineUpdates.INTERVAL_DEFAULT, false) {
                EngineUpdates.schedule(ctx)
            }
        }
        choice(b, nav, T.updatesInstall, EngineUpdates.INSTALL, Options.updateInstall, EngineUpdates.INSTALL_ASK, false)
        if (b.prefs.int(EngineUpdates.INSTALL, EngineUpdates.INSTALL_ASK) == EngineUpdates.INSTALL_AUTO) {
            toggle(b, T.updatesWifiOnly, T.updatesWifiOnlySummary, EngineUpdates.WIFI_ONLY, true, false)
        }
        item(key = "updateSource") {
            SettingRow(T.updatesSource, remember(revision) { Sources.updateSource(ctx).name }, onClick = {
                nav.push(T.updatesSource) { n -> UpdateSourcePage(b, n) { revision++ } }
            })
        }
        nav(nav, T.updatesSources, Sources.enabled(ctx).joinToString(", ") { it.name }) { SourcesPage(b, it) }
        section(T.updatesInstalled)
        item(key = "shellVersion") { SettingRow("Antite ${BuildConfig.VERSION_NAME}", T.updatesShellBuild(code = BuildConfig.VERSION_CODE)) }
        item(key = "active") { SettingRow("${b.engine.info.type} ${i.active ?: b.engine.info.version}", T.updatesRunning(origin = from(i.activeFrom))) }
        if (i.pending != null) item(key = "pending") { SettingRow("gecko ${i.pending}", T.updatesAfterRestart(origin = from(i.pendingFrom))) }
        if (i.previous != null) {
            item(key = "previous") {
                SettingRow("gecko ${i.previous}", T.updatesPrevious(origin = from(i.previousFrom)),
                    onClick = { rollBack(i) })
            }
        }
        note(T.updatesRollbackNote(count = EngineStore.MAX_FAILED_STARTS))
        section(T.updatesDebug)
        item(key = "debug") { DebugInstall(b) { revision++ } }
    }
}

@Composable
private fun DebugInstall(b: Browser, changed: () -> Unit) {
    val ctx = b.activity
    // Kept in the browser prefs, so the row follows it even when the unlock prompt recreates this item.
    val on = b.prefs.bool(EngineUpdates.DEBUG, false)
    var busy by remember { mutableStateOf<String?>(null) }
    SwitchRow(T.updatesDebugMode, T.updatesDebugModeSummary, on) { v ->
        if (!v) {
            b.prefs.edit { putBoolean(EngineUpdates.DEBUG, false) }
        } else {
            b.confirm(T.updatesDebugWarning, T.updatesDebugEnable) {
                Auth.require(ctx, T.updatesDebugEnableAuth, { b.prefs.edit { putBoolean(EngineUpdates.DEBUG, true) } }, {})
            }
        }
    }
    if (!on) return
    fun install(updates: dev.antite.browser.engine.Updates) {
        val choices = listOfNotNull(
            updates.engine?.let { o ->
                DialogButton(T.updatesDebugEngine(version = o.entry.version, size = (o.size + 512 * 1024) / (1024 * 1024))) {
                    busy = T.engineDownloading
                    b.engineNews.install(o, { busy = T.updatesDebugEngineDownloadingPercent(percent = it) }) {
                        busy = null
                        changed()
                    }
                }
            },
            updates.shell?.let { s ->
                DialogButton(T.shellDownloadName(version = s.version)) {
                    busy = T.updatesDebugShellDownloading
                    b.engineNews.installShell(s, { busy = T.updatesDebugShellDownloadingPercent(percent = it) }) { busy = null }
                }
            },
        )
        if (choices.isEmpty()) b.toast(T.updatesDebugNothing)
        else b.show(UiDialog.Items(T.updatesDebugInstall, choices))
    }
    SettingRow(T.updatesDebugFromAddress, busy ?: T.updatesDebugFromAddressSummary, onClick = {
        if (busy != null) return@SettingRow
        b.show(UiDialog.Form(T.updatesDebugFromAddress, null, listOf(
            FormField(T.updatesDebugIndexAddress, b.prefs.string("debugUrl", ""), kind = FormField.Kind.URL),
        ), T.updatesDebugCheck, { v ->
            val url = v[0].trim()
            b.prefs.edit { putString("debugUrl", url) }
            busy = T.updatesChecking
            ctx.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { EngineUpdates.fromAddress(ctx, url) } }
                busy = null
                r.onSuccess(::install).onFailure { b.toast(T.updatesDebugFailed(reason = it.message ?: it.javaClass.simpleName)) }
            }
            true
        }))
    })
}

private fun updatesSummary(b: Browser): String =
    if (!b.prefs.bool(EngineUpdates.AUTO, true)) T.updatesCheckOff
    else T.updatesCheckInterval(interval = Options.updateInterval[b.prefs.index(EngineUpdates.INTERVAL, Options.updateInterval,
        EngineUpdates.INTERVAL_DEFAULT)].label.lowercase())

@Composable
private fun UpdateSourcePage(b: Browser, nav: Nav, changed: () -> Unit) {
    val ctx = b.activity
    var revision by remember { mutableIntStateOf(0) }
    val sources = remember(revision) { Sources.enabled(ctx) }
    val current = remember(revision) { Sources.updateSource(ctx).id }
    RadioPage(b, nav, T.updatesSource, sources.map { it.name }, { sources.indexOfFirst { it.id == current } }) { i ->
        val s = sources[i]
        if (s.id == current) return@RadioPage
        b.confirm(T.updatesSourceConfirm(source = s.name, fingerprint = s.fingerprint), T.updatesSourceChange) {
            try {
                Sources.setUpdateSource(ctx, s.id)
                revision++
                changed()
            } catch (e: SourceException) {
                b.toast(e.message ?: "")
            }
        }
    }
}

@Composable
private fun SourcesPage(b: Browser, nav: Nav) {
    val ctx = b.activity
    var revision by remember { mutableIntStateOf(0) }
    val list = remember(revision) { Sources.list(ctx) }
    fun addSource() = b.show(UiDialog.Form(T.sourcesAdd, T.sourcesAddWarning,
        listOf(
            FormField(T.ntpTileName, kind = FormField.Kind.TEXT),
            FormField(T.engineSourceIndexUrl, kind = FormField.Kind.URL),
            FormField(T.engineSourceKey, kind = FormField.Kind.URL),
        ), T.actionNext, { v ->
            val key = try {
                Sources.parseKey(v[2])
            } catch (e: SourceException) {
                b.toast(e.message ?: "")
                return@Form false
            }
            b.confirm(T.sourcesFingerprintConfirm(fingerprint = Sources.fingerprint(key)), T.actionAdd) {
                try {
                    Sources.add(ctx, v[0].trim(), v[1].trim(), key)
                    revision++
                } catch (e: SourceException) {
                    b.toast(e.message ?: "")
                }
            }
            true
        }))
    Screen(b, nav, T.updatesSources) {
        note(T.sourcesNote)
        list.forEach { s ->
            item(key = "src:${s.id}") {
                SettingRow(s.name, (if (s.enabled) "" else T.sourcesDisabled + "\n") + s.url, onClick = {
                    nav.push(s.name) { n -> SourcePage(b, n, s.id) { revision++ } }
                })
            }
        }
        item(key = "add") { SettingRow(T.sourcesAdd, onClick = ::addSource) }
    }
}

@Composable
private fun SourcePage(b: Browser, nav: Nav, id: String, changed: () -> Unit) {
    val ctx = b.activity
    var revision by remember { mutableIntStateOf(0) }
    val s = remember(revision) { Sources.find(ctx, id) } ?: return
    val isUpdateSource = remember(revision) { Sources.updateSource(ctx).id == id }
    var busy by remember { mutableStateOf<String?>(null) }
    fun checkThis() = ctx.lifecycleScope.launch {
        busy = T.updatesChecking
        val (found, offer) = withContext(Dispatchers.IO) {
            EngineUpdates.fromSource(ctx, s).let { it to EngineUpdates.newerThanInstalled(ctx, it.offer) }
        }
        busy = null
        when {
            found.errors.isNotEmpty() -> b.toast(T.sourceCheckFailed(reason = found.errors.joinToString()))
            offer == null -> b.toast(found.offer?.let { T.sourceNotNewer(version = it.entry.version) }
                ?: T.sourceNoEngine)
            else -> b.confirm(T.sourceOfferConfirm(source = s.name, version = offer.entry.version,
                size = (offer.size + 512 * 1024) / (1024 * 1024), updateSource = Sources.updateSource(ctx).name), T.actionDownload) {
                busy = T.updatesDownloading
                b.engineNews.install(offer, { busy = T.updatesDownloadingPercent(percent = it) }) { busy = null }
            }
        }
    }
    Screen(b, nav, s.name) {
        item(key = "enabled") {
            val summary = listOfNotNull(if (s.builtin) T.sourceBuiltin else null,
                if (isUpdateSource) T.updatesSource else null).joinToString(". ").ifEmpty { null }
            SwitchRow(T.sourceUse, summary, s.enabled) { v ->
                try {
                    Sources.setEnabled(ctx, id, v)
                    revision++
                    changed()
                } catch (e: SourceException) {
                    b.toast(e.message ?: "")
                }
            }
        }
        if (!isUpdateSource) {
            item(key = "check") {
                SettingRow(T.sourceCheckThis, busy ?: T.sourceCheckThisSummary,
                    onClick = { if (busy == null) checkThis() })
            }
        }
        item(key = "url") { SettingRow(T.sourceAddress, s.url) }
        item(key = "key") { SettingRow(T.sourceFingerprint, s.fingerprint) }
        if (!s.builtin) {
            item(key = "remove") {
                SettingRow(T.sourceDelete, onClick = {
                    b.confirm(T.sourceDeleteConfirm(source = s.name), T.actionDelete) {
                        try {
                            Sources.remove(ctx, id)
                            changed()
                            nav.back()
                        } catch (e: SourceException) {
                            b.toast(e.message ?: "")
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun DnsPage(b: Browser, nav: Nav) {
    val doh = b.prefs.index("doh", Options.doh, 0)
    Screen(b, nav, T.privacySecureDns) {
        item {
            SwitchRow(T.dnsUse, T.dnsUseSummary, doh != 0) { on ->
                b.prefs.edit { putInt("doh", if (on) maxOf(1, b.prefs.int("dohLast", 1)) else 0) }
                b.applyEngineSettings()
            }
        }
        if (doh != 0) {
            section(T.dnsProvider)
            Options.doh.drop(1).forEachIndexed { i, o ->
                item {
                    RadioRow(o.label, doh == i + 1) {
                        b.prefs.edit { putInt("doh", i + 1); putInt("dohLast", i + 1) }
                        b.applyEngineSettings()
                    }
                }
            }
            choice(b, nav, T.dnsMode, "dohMode", Options.dohModes, 0, true)
        }
    }
}

@Composable
private fun HomePage(b: Browser, nav: Nav) {
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
private fun TabsPage(b: Browser, nav: Nav) = Screen(b, nav, T.settingsTabs) {
    choice(b, nav, T.tabsLayout, "tabLayout", Options.tabLayouts, 0, false)
    choice(b, nav, T.tabsStartup, "startup", Options.startup, 0, false)
    choice(b, nav, T.tabsExternal, "external", Options.external, 0, false)
    toggle(b, T.tabsCloseIncognito, null, "closeIncognito", false, false)
    toggle(b, T.tabsDesktopDefault, T.tabsDesktopDefaultSummary, "desktopDefault", false, false)
}

@Composable
private fun AccessibilityPage(b: Browser, nav: Nav) = Screen(b, nav, T.settingsAccessibility) {
    choice(b, nav, T.a11yFont, "font", Options.fonts, 2, true)
    toggle(b, T.a11yForceZoom, T.a11yForceZoomSummary,
        "forceZoom", false, true)
    toggle(b, T.a11yDoubleTapZoom, null, "doubleTapZoom", true, true)
}

@Composable
private fun SiteSettingsPage(b: Browser, nav: Nav) = Screen(b, nav, T.settingsSites) {
    nav(nav, T.sitesAll, T.sitesAllSummary) { AllSitesPage(b, it) }
    section(T.sitesPermissions)
    SitePerms.ALL.forEach { c -> nav(nav, c.title, c.labels[b.perms.defaultIndex(c)]) { CategoryPage(b, it, c) } }
    note(T.sitesClipboardNote)
}

@Composable
private fun CategoryPage(b: Browser, nav: Nav, c: SitePerms.Cat) {
    val cur = b.perms.defaultIndex(c)
    fun setDefault(i: Int) {
        b.perms.setDefaultIndex(c, i)
        b.applyEngineSettings()
    }
    Screen(b, nav, c.title) {
        if (c.labels.size == 2) {
            item { SwitchRow(c.title, c.labels[cur], cur == 0) { on -> setDefault(if (on) 0 else 1) } }
        } else {
            section(T.sitesDefault)
            c.labels.forEachIndexed { i, l -> item { RadioRow(l, i == cur) { setDefault(i) } } }
        }
        if (c == SitePerms.NOTIFICATIONS) {
            note(T.sitesNotificationsNote)
        }
        if (!c.perSite) {
            note(c.note ?: "")
            return@Screen
        }
        section(T.sitesExceptions)
        val ex = b.perms.exceptions(c)
        if (ex.isEmpty()) note(T.sitesNoExceptions)
        ex.forEach { (h, v) -> item(key = "ex:$h") { SettingRow(h, b.perms.label(c, v), onClick = { exceptionPage(b, nav, c, h) }) } }
        item {
            SettingRow(T.sitesAdd, onClick = {
                b.show(UiDialog.Form(T.sitesAdd, null, listOf(FormField("example.com", "", FormField.Kind.URL)), T.actionNext,
                    onConfirm = { v ->
                        val h = Urls.hostOfInput(v[0])
                        if (h.isNotEmpty()) exceptionPage(b, nav, c, h)
                        true
                    }))
            })
        }
    }
}

private fun exceptionPage(b: Browser, nav: Nav, c: SitePerms.Cat, h: String) {
    val pairs = c.values.indices.filter { c.values[it] != SitePerms.ASK }.map { c.labels[it] to c.values[it] }.toMutableList()
    if (pairs.none { it.second == SitePerms.ALLOW }) pairs.add(0, T.permAllow to SitePerms.ALLOW)
    pairs += T.sitesDefaultWithValue(value = c.labels[b.perms.defaultIndex(c)].lowercase(Locale.ROOT)) to -1
    nav.push("$h · ${c.title}") { n ->
        RadioPage(b, n, "$h · ${c.title}", pairs.map { it.first }, { pairs.indexOfFirst { it.second == b.perms.exception(c, h) } }) { i ->
            b.perms.setException(c, h, pairs[i].second)
        }
    }
}

@Composable
private fun AllSitesPage(b: Browser, nav: Nav) {
    val tracking by produceState(emptyList<String>()) {
        value = suspendCancellableCoroutine { cont ->
            b.engine.permissions.list { list ->
                cont.resume(list.filter { !it.privateMode && it.permission == SitePermission.TRACKING_EXCEPTION }
                    .mapNotNull { android.net.Uri.parse(it.origin).host })
            }
        }
    }
    val hosts = (b.perms.hosts() + tracking).distinct().sorted()
    Screen(b, nav, T.sitesAll) {
        if (hosts.isEmpty()) note(T.sitesNoneYet)
        hosts.forEach { h ->
            item(key = h) {
                val n = SitePerms.ALL.count { b.perms.exception(it, h) >= 0 }
                SettingRow(h, T.sitesSpecialCount(count = n),
                    onClick = { nav.push(h) { SitePage(b, it, h) } })
            }
        }
    }
}

@Composable
private fun SitePage(b: Browser, nav: Nav, h: String) {
    var revision by remember { mutableIntStateOf(0) }
    val trackingOff by produceState(false, revision) {
        value = suspendCancellableCoroutine { cont ->
            b.engine.permissions.list { list ->
                cont.resume(list.any {
                    it.permission == SitePermission.TRACKING_EXCEPTION && Urls.siteHost(it.origin) == h &&
                        it.value == PermissionValue.ALLOW
                })
            }
        }
    }
    Screen(b, nav, h) {
        section(T.sitesPermissions)
        SitePerms.ALL.filter { it.perSite }.forEach { c ->
            item(key = c.key) {
                val ex = b.perms.exception(c, h)
                SettingRow(c.title, if (ex >= 0) b.perms.label(c, ex)
                else T.sitesDefaultIs(value = c.labels[b.perms.defaultIndex(c)].lowercase(Locale.ROOT)),
                    onClick = { exceptionPage(b, nav, c, h) })
            }
        }
        section(T.sitesProtection)
        item {
            SwitchRow(T.privacyTracking, if (trackingOff) T.sitesTrackingOff else T.sitesTrackingOn, !trackingOff) { on ->
                b.engine.permissions.set("https://$h", SitePermission.TRACKING_EXCEPTION,
                    if (on) PermissionValue.BLOCK else PermissionValue.ALLOW)
                revision++
            }
        }
        section(T.sitesData)
        item {
            SettingRow(T.sitesClear, T.sitesClearSummary(host = h), onClick = {
                b.confirm(T.sitesClearConfirm(host = h), T.actionDelete) {
                    b.perms.forgetSite(h)
                    b.engine.storage.clearSite(h) { ok -> if (ok) b.toast(T.clearDataDone) }
                    revision++
                }
            })
        }
    }
}

@Composable
private fun LanguagesPage(b: Browser, nav: Nav) {
    val tr = b.engine.translation
    val cur = b.prefs.locales
    fun save(list: List<String>) {
        b.prefs.edit { putString("langs", list.joinToString(",")) }
        b.applyEngineSettings()
    }
    Screen(b, nav, T.settingsLanguages) {
        item(key = "uiLanguage") {
            val options = listOf("" to T.languagesSystem) + L10n.languages
            fun current() = options.indexOfFirst { it.first == b.prefs.string(L10n.PREF, "") }.coerceAtLeast(0)
            SettingRow(T.languagesInterface, options[current()].second, onClick = {
                nav.push(T.languagesInterface) { n ->
                    RadioPage(b, n, T.languagesInterface, options.map { it.second }, ::current) { i ->
                        b.prefs.edit { putString(L10n.PREF, options[i].first) }
                        L10n.init(b.activity)
                    }
                }
            })
        }
        section(T.languagesSites)
        note(T.languagesSitesNote)
        if (cur.isEmpty()) item { SettingRow(Urls.langName(Locale.getDefault().language), T.languagesSystem) }
        cur.forEachIndexed { i, code ->
            item(key = "lang:$code") {
                SettingRow(Urls.langName(code), if (i == 0) T.languagesPrimary else null, onClick = {
                    b.show(UiDialog.Items(Urls.langName(code), buildList {
                        if (i > 0) add(DialogButton(T.languagesMakePrimary) { save(listOf(code) + (cur - code)) })
                        add(DialogButton(T.actionDelete) { save(cur - code) })
                    }))
                })
            }
        }
        item {
            SettingRow(T.languagesAdd, onClick = {
                val have = cur.ifEmpty { listOf(Locale.getDefault().language) }
                nav.push(T.languagesAdd) { n ->
                    Screen(b, n, T.languagesAdd) {
                        Options.languages.filter { it !in have }.forEach { code ->
                            item { SettingRow(Urls.langName(code), onClick = { save(have + code); n.back() }) }
                        }
                    }
                }
            })
        }
        if (tr != null) {
            section(T.languagesTranslation)
            toggle(b, T.languagesOfferTranslation, T.languagesOfferTranslationSummary,
                "translateOffer", true, true)
            item {
                SettingRow(T.languagesTranslateTo, Urls.langName(b.prefs.translateTarget), onClick = {
                    tr.supported({ _, to ->
                        nav.push(T.languagesTranslateTo) { n ->
                            RadioPage(b, n, T.languagesTranslateTo, to.map { it.name }, { to.indexOfFirst { it.code == b.prefs.translateTarget } }) { i ->
                                b.prefs.edit { putString("translateTo", to[i].code) }
                            }
                        }
                    }, { b.toast(T.translateUnavailable) })
                })
            }
            nav(nav, T.languagesModels, T.languagesModelsSummary) { ModelsPage(b, it) }
            nav(nav, T.languagesNeverTranslate, null) { NeverTranslatePage(b, it) }
        }
    }
}

@Composable
private fun ModelsPage(b: Browser, nav: Nav) {
    val tr = b.engine.translation ?: return
    var revision by remember { mutableIntStateOf(0) }
    val models by produceState<List<LanguageModel>?>(null, revision) {
        value = suspendCancellableCoroutine { cont -> tr.models({ cont.resume(it) }, { cont.resume(emptyList()) }) }
    }
    Screen(b, nav, T.languagesModels) {
        note(T.modelsNote)
        val list = models ?: return@Screen
        if (list.isEmpty()) note(T.modelsEmpty)
        list.forEach { m ->
            item(key = m.language.code) {
                val size = android.text.format.Formatter.formatShortFileSize(b.activity, m.size)
                SettingRow(m.language.name, if (m.downloaded) T.modelDownloaded(size = size) else T.modelNotDownloaded(size = size), onClick = {
                    val done: (Boolean) -> Unit = { ok -> if (ok) revision++ else b.toast(T.modelActionFailed) }
                    if (m.downloaded) tr.deleteModel(m.language.code, done) else tr.downloadModel(m.language.code, done)
                })
            }
        }
    }
}

@Composable
private fun NeverTranslatePage(b: Browser, nav: Nav) {
    val tr = b.engine.translation ?: return
    var revision by remember { mutableIntStateOf(0) }
    val sites by produceState(emptyList<String>(), revision) {
        value = suspendCancellableCoroutine { cont -> tr.neverTranslateSites { cont.resume(it) } }
    }
    Screen(b, nav, T.languagesNeverTranslate) {
        if (sites.isEmpty()) note(T.neverTranslateEmpty)
        sites.forEach { s ->
            item(key = s) { SettingRow(s, T.neverTranslateRow, onClick = { tr.allowTranslateSite(s) { revision++ } }) }
        }
    }
}

@Composable
private fun PasswordsPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val data by produceState<Pair<List<SavedLogin>, List<String>>?>(null, revision) {
        value = b.vault.io { logins(null) to nevers() }
    }
    Screen(b, nav, T.settingsPasswords) {
        toggle(b, T.passwordsOfferSave, null, "passwordsSave", true, true)
        toggle(b, T.passwordsAutofill, T.passwordsAutofillSummary, "passwordsAutofill", true, true)
        val (logins, nevers) = data ?: return@Screen
        section(T.passwordsSection)
        if (logins.isEmpty()) note(T.passwordsEmpty)
        logins.forEach { l ->
            item(key = l.guid) {
                SettingRow(l.host, l.username.ifEmpty { T.passwordNoUsername }, onClick = {
                    Auth.require(b.activity, T.passwordsShow, { nav.push(l.host) { LoginPage(b, it, l) } }, {})
                })
            }
        }
        if (nevers.isNotEmpty()) {
            section(T.passwordsNeverSave)
            nevers.forEach { h ->
                item(key = "never:$h") {
                    SettingRow(h, T.passwordsNeverSaveRow, onClick = {
                        b.activity.lifecycleScope.launch { b.vault.io { removeNever(h) }; revision++ }
                    })
                }
            }
        }
        section(T.passwordsTransfer)
        item { SettingRow(T.passwordsImport, T.passwordsImportSummary, onClick = { importCsv(b) { revision++ } }) }
        item {
            SettingRow(T.passwordsExport, T.passwordsExportSummary, onClick = {
                Auth.require(b.activity, T.passwordsExportAuth, { exportCsv(b) }, {})
            })
        }
    }
}

private fun importCsv(b: Browser, done: () -> Unit) = b.activity.openDocument(listOf("text/*", "text/csv", "text/comma-separated-values")) { uri ->
    if (uri == null) return@openDocument
    b.activity.lifecycleScope.launch {
        val n = b.vault.io {
            runCatching { b.activity.contentResolver.openInputStream(uri)!!.use { importCsv(it) } }.getOrDefault(-1)
        }
        b.toast(if (n < 0) T.passwordsReadFailed else T.passwordsImported(count = n))
        done()
    }
}

private fun exportCsv(b: Browser) = b.activity.createDocument("Antite Passwords.csv", "text/csv") { uri ->
    if (uri == null) return@createDocument
    b.activity.lifecycleScope.launch {
        val n = b.vault.io {
            runCatching { b.activity.contentResolver.openOutputStream(uri)!!.use { exportCsv(it) } }.getOrDefault(-1)
        }
        b.toast(if (n < 0) T.passwordsWriteFailed else T.passwordsExported(count = n))
    }
}

@Composable
private fun LoginPage(b: Browser, nav: Nav, l: SavedLogin) {
    var revision by remember { mutableIntStateOf(0) }
    val password by produceState<String?>(null, revision) { value = b.vault.io { password(l.guid) } }
    var shown by remember { mutableStateOf(false) }
    Screen(b, nav, l.host) {
        val pass = password ?: ""
        item { SettingRow(T.passwordSite, l.origin) }
        item { SettingRow(T.promptUsername, l.username.ifEmpty { T.passwordNone }, onClick = { b.activity.copy(l.username) }) }
        item { SettingRow(T.promptPassword, if (shown) pass else "••••••••", onClick = { shown = !shown }) }
        note(T.passwordTapToShow)
        item { SettingRow(T.passwordCopy, onClick = { b.activity.copy(pass, sensitive = true) }) }
        item {
            SettingRow(T.actionEdit, onClick = {
                b.show(UiDialog.Form(T.passwordEdit, null, listOf(
                    FormField(T.promptUsername, l.username),
                    FormField(T.promptPassword, pass, FormField.Kind.VISIBLE_PASSWORD),
                ), onConfirm = { v ->
                    if (v[1].isEmpty()) return@Form false
                    b.activity.lifecycleScope.launch { b.vault.io { editLogin(l.guid, v[0], v[1]) }; revision++ }
                    true
                }))
            })
        }
        item {
            SettingRow(T.actionDelete, onClick = {
                b.confirm(T.passwordDeleteConfirm(host = l.host), T.actionDelete) {
                    b.activity.lifecycleScope.launch { b.vault.io { deleteLogin(l.guid) } }
                    nav.back()
                }
            })
        }
    }
}

@Composable
private fun CardsPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val cards by produceState(emptyList<dev.antite.browser.data.SavedCard>(), revision) { value = b.vault.io { cards() } }
    fun form(card: Card?) = b.show(UiDialog.Form(if (card == null) T.cardNew else T.cardEdit, null, listOf(
        FormField(T.cardNumber, card?.number ?: "", FormField.Kind.NUMBER),
        FormField(T.cardName, card?.name ?: "", FormField.Kind.CAPS),
        FormField(T.cardMonth, card?.expirationMonth ?: "", FormField.Kind.NUMBER),
        FormField(T.cardYear, card?.expirationYear ?: "", FormField.Kind.NUMBER),
    ), onConfirm = { v ->
        val digits = v[0].filter { it.isDigit() }
        if (digits.length < 12) {
            b.toast(T.cardBadNumber)
            return@Form false
        }
        val c = Card(card?.guid, v[1].trim(), digits, v[2].trim(), v[3].trim())
        b.activity.lifecycleScope.launch { b.vault.io { saveCard(c) }; revision++ }
        true
    }))
    Screen(b, nav, T.settingsPayment) {
        toggle(b, T.cardsEnabled, T.cardsEnabledSummary,
            "cardsEnabled", true, true)
        section(T.cardsSection)
        if (cards.isEmpty()) note(T.cardsEmpty)
        cards.forEach { c ->
            item(key = c.guid) {
                SettingRow(c.label, c.name, onClick = {
                    b.show(UiDialog.Items(c.label, listOf(
                        DialogButton(T.actionEdit) {
                            Auth.require(b.activity, T.cardEdit, {
                                b.activity.lifecycleScope.launch {
                                    b.vault.io { fullCards() }.firstOrNull { it.guid == c.guid }?.let(::form)
                                }
                            }, {})
                        },
                        DialogButton(T.actionDelete) {
                            b.confirm(T.cardDeleteConfirm(card = c.label), T.actionDelete) {
                                b.activity.lifecycleScope.launch { b.vault.io { deleteCard(c.guid) }; revision++ }
                            }
                        },
                    )))
                })
            }
        }
        item { SettingRow(T.cardAdd, onClick = { form(null) }) }
    }
}

@Composable
private fun AddressesPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val list by produceState(emptyList<Address>(), revision) { value = b.vault.io { addresses() } }
    fun form(a: Address?) = b.show(UiDialog.Form(if (a == null) T.addressNew else T.addressEdit, null, listOf(
        FormField(T.addressName, a?.name ?: "", FormField.Kind.NAME),
        FormField(T.addressOrganization, a?.organization ?: "", FormField.Kind.NAME),
        FormField(T.addressStreet, a?.streetAddress ?: "", FormField.Kind.NAME),
        FormField(T.addressCity, a?.addressLevel2 ?: "", FormField.Kind.NAME),
        FormField(T.addressRegion, a?.addressLevel1 ?: "", FormField.Kind.NAME),
        FormField(T.addressPostalCode, a?.postalCode ?: "", FormField.Kind.NUMBER),
        FormField(T.addressCountry, a?.country ?: "", FormField.Kind.CAPS),
        FormField(T.addressPhone, a?.tel ?: "", FormField.Kind.PHONE),
        FormField(T.addressEmail, a?.email ?: "", FormField.Kind.EMAIL),
    ), onConfirm = { v ->
        val out = (a ?: Address(null)).copy(
            name = v[0].trim(), organization = v[1].trim(), streetAddress = v[2].trim(), addressLevel2 = v[3].trim(),
            addressLevel1 = v[4].trim(), postalCode = v[5].trim(), country = v[6].trim(), tel = v[7].trim(), email = v[8].trim(),
        )
        b.activity.lifecycleScope.launch { b.vault.io { saveAddress(out) }; revision++ }
        true
    }))
    Screen(b, nav, T.settingsAddresses) {
        toggle(b, T.addressesEnabled, T.addressesEnabledSummary, "addressesEnabled", true, true)
        section(T.addressesSection)
        if (list.isEmpty()) note(T.addressesEmpty)
        list.forEach { a ->
            item(key = a.guid) {
                SettingRow(addressLine(a), onClick = {
                    b.show(UiDialog.Items(addressLine(a), listOf(
                        DialogButton(T.actionEdit) { form(a) },
                        DialogButton(T.actionDelete) {
                            b.confirm(T.addressDeleteConfirm, T.actionDelete) {
                                b.activity.lifecycleScope.launch { b.vault.io { deleteAddress(a.guid!!) }; revision++ }
                            }
                        },
                    )))
                })
            }
        }
        item { SettingRow(T.addressAdd, onClick = { form(null) }) }
    }
}
