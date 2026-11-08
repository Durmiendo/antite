package dev.antite.browser.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.mutableIntStateOf
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

object Options {
    val engines = listOf(
        Option("DuckDuckGo", "https://duckduckgo.com/?q="),
        Option("Google", "https://www.google.com/search?q="),
        Option("Яндекс", "https://yandex.ru/search/?text="),
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
    val tracking = listOf(
        Option("Стандартная", TrackingProtection.STANDARD),
        Option("Строгая", TrackingProtection.STRICT),
        Option("Выключена", TrackingProtection.OFF),
    )
    val cookies = listOf(
        Option("Полная защита (изолировать сторонние)", CookiePolicy.ISOLATE_THIRD_PARTY),
        Option("Блокировать сторонние трекеры", CookiePolicy.BLOCK_TRACKERS),
        Option("Блокировать все сторонние", CookiePolicy.BLOCK_THIRD_PARTY),
        Option("Разрешить все", CookiePolicy.ALLOW_ALL),
    )
    val doh = listOf<Option<String?>>(
        Option("Выключено", null),
        Option("Cloudflare", "https://mozilla.cloudflare-dns.com/dns-query"),
        Option("Quad9", "https://dns.quad9.net/dns-query"),
        Option("Mullvad", "https://dns.mullvad.net/dns-query"),
        Option("AdGuard (с блокировкой рекламы)", "https://dns.adguard-dns.com/dns-query"),
    )
    val dohModes = listOf(
        Option("Если доступен (иначе обычный DNS)", DnsOverHttpsMode.FALLBACK),
        Option("Только защищённый DNS", DnsOverHttpsMode.ONLY),
    )
    val fonts = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f).map { Option("${(it * 100).toInt()}%", it) }
    val startup = listOf(Option("Последние вкладки", 0), Option("Новая вкладка", 1))
    val external = listOf(Option("В новой вкладке", 0), Option("В текущей вкладке", 1))
    val themes = listOf(
        Option("Как в системе", ColorScheme.SYSTEM),
        Option("Светлая", ColorScheme.LIGHT),
        Option("Тёмная", ColorScheme.DARK),
    )
    val webRtc = listOf(
        Option("Без ограничений", WebRtcPolicy.DEFAULT),
        Option("Не раскрывать локальный IP", WebRtcPolicy.HIDE_LOCAL_IP),
        Option("Только через прокси", WebRtcPolicy.PROXY_ONLY),
        Option("Отключить WebRTC", WebRtcPolicy.DISABLED),
    )
    val fingerprint = listOf(
        Option("Стандартная (в инкогнито)", FingerprintProtection.PRIVATE_ONLY),
        Option("Усиленная (везде)", FingerprintProtection.ALWAYS),
        Option("Максимальная (resistFingerprinting)", FingerprintProtection.MAXIMUM),
    )
    val updateInstall = listOf(Option("Спрашивать", 0), Option("Автоматически", 1))
    val tabLayouts = listOf(Option("Сетка", 0), Option("Список (как в Яндексе)", 1))
    val homeModes = listOf(Option("Новая вкладка", 0), Option("Свой адрес", 1))
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
            offerTranslation = bool("translateOffer", true),
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
