package dev.antite.browser.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import dev.antite.browser.Browser
import dev.antite.browser.clearDataDialog
import dev.antite.browser.data.Options
import dev.antite.browser.engine.Sources
import dev.antite.browser.l10n.T
import dev.antite.browser.openUboDashboard
import dev.antite.engine.BackgroundService

@Composable
internal fun PrivacyPage(b: Browser, nav: Nav) {
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

internal fun serviceSummary(s: BackgroundService, on: Boolean) = buildString {
    append(s.summary).append("\n").append(T.networkDestination(hosts = s.hosts.joinToString(", ")))
    if (!on && s.offEffect != null) append("\n").append(T.networkWhenOff(effect = s.offEffect))
}

@Composable
internal fun NetworkPage(b: Browser, nav: Nav) = Screen(b, nav, T.privacyNetwork) {
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

@Composable
internal fun DnsPage(b: Browser, nav: Nav) {
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
