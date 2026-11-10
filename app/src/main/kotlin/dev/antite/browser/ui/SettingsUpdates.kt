package dev.antite.browser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.antite.browser.UiDialog
import dev.antite.browser.data.Options
import dev.antite.browser.engine.DebugAddress
import dev.antite.browser.engine.EngineUpdates
import dev.antite.browser.engine.Engines
import dev.antite.browser.engine.EngineStore
import dev.antite.browser.engine.SourceException
import dev.antite.browser.engine.Sources
import dev.antite.browser.l10n.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal class UpdateInfo(
    val active: String?, val activeFrom: String?,
    val pending: String?, val pendingFrom: String?,
    val previous: String?, val previousFrom: String?,
    val lastCheck: Long, val lastError: String?, val available: String?, val shell: Int,
)

internal fun from(origin: String?) = if (origin == null) "" else T.updatesOrigin(origin = origin)

@Composable
internal fun UpdatesPage(b: Browser, nav: Nav) {
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
internal fun DebugInstall(b: Browser, changed: () -> Unit) {
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
        if (choices.isEmpty()) b.show(UiDialog.Message(null, T.updatesDebugNothing, buttons = listOf(DialogButton(T.actionOk))))
        else b.show(UiDialog.Items(T.updatesDebugInstall, choices))
    }
    fun failed(text: String) = b.show(UiDialog.Message(T.updatesDebugCheckFailed, text, buttons = listOf(DialogButton(T.actionOk))))
    SettingRow(T.updatesDebugFromAddress, busy ?: T.updatesDebugFromAddressSummary, onClick = {
        if (busy != null) return@SettingRow
        b.show(UiDialog.Form(T.updatesDebugFromAddress, null, listOf(
            FormField(T.updatesDebugIndexAddress, b.prefs.string("debugUrl", ""), kind = FormField.Kind.URL),
        ), T.updatesDebugCheck, { v ->
            val input = v[0].trim()
            b.prefs.edit { putString("debugUrl", input) }
            val url = try {
                DebugAddress.indexUrl(input)
            } catch (e: IllegalArgumentException) {
                failed(T.updatesDebugBadAddress(address = input))
                return@Form true
            }
            busy = T.updatesDebugConnecting(address = DebugAddress.hostPort(url))
            ctx.lifecycleScope.launch {
                val start = System.currentTimeMillis()
                val r = withContext(Dispatchers.IO) { runCatching { EngineUpdates.fromAddress(ctx, url) } }
                busy = null
                r.onSuccess(::install).onFailure { failed(debugFailure(it, url, System.currentTimeMillis() - start) + "\n\n" + url) }
            }
            true
        }))
    })
}

private fun debugFailure(e: Throwable, url: String, millis: Long): String {
    val address = DebugAddress.hostPort(url)
    val port = DebugAddress.port(url)
    // Android's HTTP stack reports a refused and an unreachable host as the same ConnectException without the errno;
    // a refusal comes back at once, an unreachable host only after seconds.
    return when (e) {
        is UnknownHostException -> T.updatesDebugUnknownHost(host = java.net.URI(url).host)
        is ConnectException if millis < 2000 -> T.updatesDebugRefused(address = address, port = port)
        is SocketTimeoutException, is ConnectException, is NoRouteToHostException -> T.updatesDebugNoAnswer(address = address, port = port)
        is JSONException -> T.updatesDebugNotIndex
        else -> T.updatesDebugFailed(reason = e.message ?: e.javaClass.simpleName)
    }
}

internal fun updatesSummary(b: Browser): String =
    if (!b.prefs.bool(EngineUpdates.AUTO, true)) T.updatesCheckOff
    else T.updatesCheckInterval(interval = Options.updateInterval[b.prefs.index(EngineUpdates.INTERVAL, Options.updateInterval,
        EngineUpdates.INTERVAL_DEFAULT)].label.lowercase())

@Composable
internal fun UpdateSourcePage(b: Browser, nav: Nav, changed: () -> Unit) {
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
internal fun SourcesPage(b: Browser, nav: Nav) {
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
internal fun SourcePage(b: Browser, nav: Nav, id: String, changed: () -> Unit) {
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
