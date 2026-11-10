package dev.antite.browser.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.mutableIntStateOf
import dev.antite.browser.Browser
import dev.antite.browser.l10n.T
import dev.antite.engine.BackgroundService
import dev.antite.engine.ColorScheme
import dev.antite.engine.CookiePolicy
import dev.antite.engine.DnsOverHttpsMode
import dev.antite.engine.EngineSettings
import dev.antite.engine.FingerprintProtection
import dev.antite.engine.TrackingProtection
import dev.antite.engine.WebRtcPolicy
import java.util.Locale

data class Option<T>(val label: String, val value: T)

// Lists with labels are built when read, so the labels follow the interface language.
object Options {
    val engines get() = listOf(
        Option("DuckDuckGo", "https://duckduckgo.com/?q="),
        Option("Google", "https://www.google.com/search?q="),
        Option(T.engineYandex, "https://yandex.ru/search/?text="),
        Option("Bing", "https://www.bing.com/search?q="),
        Option("Startpage", "https://www.startpage.com/do/search?q="),
    )
    val suggestUrls = listOf(
        "https://duckduckgo.com/ac/?type=list&q=",
        "https://suggestqueries.google.com/complete/search?client=firefox&q=",
        "https://suggest.yandex.ru/suggest-ff.cgi?part=",
        "https://api.bing.com/osjson.aspx?query=",
        "https://duckduckgo.com/ac/?type=list&q=",
    )
    val tracking get() = listOf(
        Option(T.trackingStandard, TrackingProtection.STANDARD),
        Option(T.trackingStrict, TrackingProtection.STRICT),
        Option(T.trackingOff, TrackingProtection.OFF),
    )
    val cookies get() = listOf(
        Option(T.cookiesIsolate, CookiePolicy.ISOLATE_THIRD_PARTY),
        Option(T.cookiesBlockTrackers, CookiePolicy.BLOCK_TRACKERS),
        Option(T.cookiesBlockThirdParty, CookiePolicy.BLOCK_THIRD_PARTY),
        Option(T.cookiesAllowAll, CookiePolicy.ALLOW_ALL),
    )
    val doh get() = listOf<Option<String?>>(
        Option(T.dohOff, null),
        Option("Cloudflare", "https://mozilla.cloudflare-dns.com/dns-query"),
        Option("Quad9", "https://dns.quad9.net/dns-query"),
        Option("Mullvad", "https://dns.mullvad.net/dns-query"),
        Option(T.dohAdguard, "https://dns.adguard-dns.com/dns-query"),
    )
    val dohModes get() = listOf(
        Option(T.dohModeFallback, DnsOverHttpsMode.FALLBACK),
        Option(T.dohModeOnly, DnsOverHttpsMode.ONLY),
    )
    val fonts = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f).map { Option("${(it * 100).toInt()}%", it) }
    val startup get() = listOf(Option(T.startupLastTabs, 0), Option(T.tabNew, 1))
    val external get() = listOf(Option(T.externalNewTab, 0), Option(T.externalCurrentTab, 1))
    val themes get() = listOf(
        Option(T.themeSystem, ColorScheme.SYSTEM),
        Option(T.themeLight, ColorScheme.LIGHT),
        Option(T.themeDark, ColorScheme.DARK),
    )
    val webRtc get() = listOf(
        Option(T.webrtcDefault, WebRtcPolicy.DEFAULT),
        Option(T.webrtcHideLocalIp, WebRtcPolicy.HIDE_LOCAL_IP),
        Option(T.webrtcProxyOnly, WebRtcPolicy.PROXY_ONLY),
        Option(T.webrtcDisabled, WebRtcPolicy.DISABLED),
    )
    val fingerprint get() = listOf(
        Option(T.fingerprintPrivate, FingerprintProtection.PRIVATE_ONLY),
        Option(T.fingerprintAlways, FingerprintProtection.ALWAYS),
        Option(T.fingerprintMaximum, FingerprintProtection.MAXIMUM),
    )
    val updateInstall get() = listOf(Option(T.updateInstallAsk, 0), Option(T.updateInstallAuto, 1))
    val updateInterval get() = listOf(
        Option(T.updateEveryMinute, 1), Option(T.updateEvery15Minutes, 15), Option(T.updateEveryHour, 60),
        Option(T.updateEvery6Hours, 360), Option(T.updateEveryDay, 1440),
    )
    val navDeadZones get() = listOf(0, 16, 32, 48, 64).map { Option(T.unitDp(value = it), it) }
    val liveTabs get() = listOf(2, 4, 6, 8, 12).map { Option(it.toString(), it) }
    val frequentSites get() = listOf(0, 4, 8, 12, 16, 24).map { Option(if (it == 0) T.ntpFrequentNone else it.toString(), it) }
    // Indexes into Motion.speeds.
    val animationSpeeds get() = listOf(T.animationOff, T.animationSlow, T.animationNormal, T.animationFast, T.animationFaster)
        .mapIndexed { i, label -> Option(label, i) }
    val tabLayouts get() = listOf(Option(T.tabLayoutGrid, 0), Option(T.tabLayoutList, 1))
    val homeModes get() = listOf(Option(T.tabNew, 0), Option(T.homeOwnAddress, 1))
    val languages = listOf(
        "ru", "en", "uk", "be", "kk", "de", "fr", "es", "it", "pt", "pl", "cs", "tr", "zh", "ja", "ko", "ar", "he",
        "hi", "fi", "sv", "nl", "el", "hu", "ro", "bg", "sr", "ka", "hy", "az", "uz", "lv", "lt", "et", "vi", "id",
        "th", "fa",
    )
}

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("antite", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    val revision = mutableIntStateOf(0)

    init {
        sp.edit().apply {
            if (sp.contains("js")) {
                if (!sp.getBoolean("js", true)) putInt("cat_js", 1)
                remove("js")
            }
            if (sp.contains("autoplay")) {
                putInt("cat_autoplay", sp.getInt("autoplay", 1))
                remove("autoplay")
            }
            if (sp.contains("safeBrowsing")) {
                putBoolean(serviceKey(BackgroundService.SAFE_BROWSING), sp.getBoolean("safeBrowsing", false))
                remove("safeBrowsing")
            }
            if (sp.contains("dark") && !sp.contains("theme")) {
                putInt("theme", if (sp.getBoolean("dark", true)) 2 else 1)
                remove("dark")
            }
        }.apply()
    }

    fun bool(key: String, def: Boolean): Boolean {
        revision.intValue
        return sp.getBoolean(key, def)
    }

    fun int(key: String, def: Int): Int {
        revision.intValue
        return sp.getInt(key, def)
    }

    fun string(key: String, def: String): String {
        revision.intValue
        return sp.getString(key, def) ?: def
    }

    fun stringSet(key: String): Set<String> {
        revision.intValue
        return sp.getStringSet(key, emptySet()) ?: emptySet()
    }

    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        revision.intValue++
    }

    fun <T> index(key: String, options: List<Option<T>>, def: Int): Int = int(key, def).coerceIn(0, options.lastIndex)

    val theme: ColorScheme get() = Options.themes[index("theme", Options.themes, 2)].value

    fun isDark(): Boolean = when (theme) {
        ColorScheme.DARK -> true
        ColorScheme.LIGHT -> false
        ColorScheme.SYSTEM -> (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    val locales: List<String>
        get() = string("langs", "").split(',').filter { it.isNotEmpty() }

    val translateTarget: String
        get() = string("translateTo", "").ifEmpty { locales.firstOrNull() ?: Locale.getDefault().language }

    val searchUrl: String get() = Options.engines[index("engine", Options.engines, 0)].value

    val suggestUrl: String get() = Options.suggestUrls[index("engine", Options.engines, 0).coerceAtMost(Options.suggestUrls.lastIndex)]

    fun serviceKey(id: String) = "bg.$id"

    fun serviceEnabled(s: BackgroundService) = bool(serviceKey(s.id), s.defaultEnabled)

    fun engineSettings(perms: SitePerms, services: List<BackgroundService>): EngineSettings {
        val doh = index("doh", Options.doh, 0)
        return EngineSettings(
            colorScheme = theme,
            fontScale = Options.fonts[index("font", Options.fonts, 2)].value,
            forceUserZoom = bool("forceZoom", false),
            doubleTapZoom = bool("doubleTapZoom", true),
            globalPrivacyControl = bool("gpc", true),
            doNotTrack = bool("dnt", false),
            httpsOnly = bool("httpsOnly", false),
            tracking = Options.tracking[index("tracking", Options.tracking, 0)].value,
            cookies = Options.cookies[index("cookies", Options.cookies, 0)].value,
            stripTrackingParams = bool("strip", true),
            fingerprinting = Options.fingerprint[index("fingerprint", Options.fingerprint, 0)].value,
            webRtc = Options.webRtc[index("webrtc", Options.webRtc, 1)].value,
            preload = bool("preload", false),
            dohMode = if (doh == 0) DnsOverHttpsMode.OFF else Options.dohModes[index("dohMode", Options.dohModes, 0)].value,
            dohUrl = Options.doh[doh].value,
            locales = locales,
            offerTranslation = Browser.TRANSLATION_ENABLED && bool("translateOffer", true),
            motionSensors = perms.defaultValue(SitePerms.SENSORS) == SitePerms.ALLOW,
            permissionDefaults = perms.engineDefaults(),
            savePasswords = bool("passwordsSave", true),
            autofillPasswords = bool("passwordsAutofill", true),
            autofillAddresses = bool("addressesEnabled", true),
            autofillCards = bool("cardsEnabled", true),
            disabledServices = services.filterNot(::serviceEnabled).map { it.id }.toSet(),
        )
    }
}
