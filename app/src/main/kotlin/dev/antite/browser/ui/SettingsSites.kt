package dev.antite.browser.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.antite.browser.Browser
import dev.antite.browser.FormField
import dev.antite.browser.UiDialog
import dev.antite.browser.Urls
import dev.antite.browser.data.SitePerms
import dev.antite.browser.l10n.T
import dev.antite.engine.PermissionValue
import dev.antite.engine.SitePermission
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun siteSettingsPage(b: Browser) = SettingsPage(T.settingsSites) { nav ->
    nav(nav, T.sitesAll, T.sitesAllSummary) { AllSitesPage(b, it) }
    section(T.sitesPermissions)
    SitePerms.ALL.forEach { c -> nav(nav, c.title, c.labels[b.perms.defaultIndex(c)]) { CategoryPage(b, it, c) } }
    note(T.sitesClipboardNote)
}

@Composable
internal fun CategoryPage(b: Browser, nav: Nav, c: SitePerms.Cat) {
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

internal fun exceptionPage(b: Browser, nav: Nav, c: SitePerms.Cat, h: String) {
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
internal fun AllSitesPage(b: Browser, nav: Nav) {
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
internal fun SitePage(b: Browser, nav: Nav, h: String) {
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
