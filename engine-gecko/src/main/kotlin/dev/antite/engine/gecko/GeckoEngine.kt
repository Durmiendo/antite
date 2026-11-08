package dev.antite.engine.gecko

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import dev.antite.engine.AutofillStore
import dev.antite.engine.BackgroundService
import dev.antite.engine.Capability
import dev.antite.engine.ColorScheme
import dev.antite.engine.CookiePolicy
import dev.antite.engine.DataKind
import dev.antite.engine.DnsOverHttpsMode
import dev.antite.engine.DownloadRequest
import dev.antite.engine.Engine
import dev.antite.engine.EngineFactory
import dev.antite.engine.EngineInfo
import dev.antite.engine.EngineSettings
import dev.antite.engine.EngineTab
import dev.antite.engine.EngineView
import dev.antite.engine.Extension
import dev.antite.engine.Extensions
import dev.antite.engine.FingerprintProtection
import dev.antite.engine.Language
import dev.antite.engine.LanguageModel
import dev.antite.engine.NotificationHandler
import dev.antite.engine.PermissionDefault
import dev.antite.engine.PermissionEntry
import dev.antite.engine.PermissionStore
import dev.antite.engine.PermissionValue
import dev.antite.engine.SitePermission
import dev.antite.engine.StorageCleaner
import dev.antite.engine.TabCallbacks
import dev.antite.engine.TabOptions
import dev.antite.engine.TrackingProtection
import dev.antite.engine.Translation
import dev.antite.engine.WebNotificationEvent
import dev.antite.engine.WebRtcPolicy
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.io.File

class GeckoEngine private constructor(internal val context: Context) : Engine {
    companion object {
        private const val ICONS_EXTENSION_ID = "icons@antite.dev"
        private const val ICONS_EXTENSION_URL = "resource://android/assets/extensions/icons/"
        private const val CONFIG = "gecko-config.yaml"
        // use-surfacecontrol: Gecko draws into a child SurfaceControl of its own size, so a resized view crops the
        // old frame instead of Android stretching it until the next one (off by default in Firefox, read at startup).
        private const val BASE_CONFIG =
            "prefs:\n  media.hardwaremediakeys.enabled: true\n  widget.android.use-surfacecontrol: true\n"
        private const val CONNECTIVITY = "connectivity"
        private const val REMOTE_SETTINGS = "remote-settings"
        private const val ADDON_UPDATES = "addon-updates"
        private const val OCSP = "ocsp"
        private val SAFE_BROWSING_HOSTS = listOf("safebrowsing.googleapis.com")
        private val REMOTE_SETTINGS_HOSTS =
            listOf("firefox.settings.services.mozilla.com", "firefox-settings-attachments.cdn.mozilla.net")

        @Volatile
        private var instance: GeckoEngine? = null

        val FACTORY = EngineFactory { context ->
            instance ?: synchronized(this) { instance ?: GeckoEngine(context.applicationContext).also { instance = it } }
        }
    }

    internal val runtime: GeckoRuntime
    internal val main = Handler(Looper.getMainLooper())
    internal var iconsExtension: WebExtension? = null
    private val liveTabs = mutableSetOf<GeckoTab>()
    private val startupPrefs = linkedMapOf<String, Any>()
    private val disk = java.util.concurrent.Executors.newSingleThreadExecutor()

    override val info = EngineInfo("gecko", org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION)

    override val capabilities = setOf(
        Capability.EXTENSIONS,
        Capability.AUTOFILL,
        Capability.TRANSLATION,
        Capability.SITE_PERMISSIONS,
        Capability.WEB_NOTIFICATIONS,
        Capability.SAVE_AS_PDF,
        Capability.MEDIA_SESSION,
    )

    init {
        runtime = GeckoRuntime.create(
            context,
            GeckoRuntimeSettings.Builder().configFilePath(writeConfig(context)).build(),
        )
        runtime.webExtensionController.ensureBuiltIn(ICONS_EXTENSION_URL, ICONS_EXTENSION_ID).accept({ e ->
            iconsExtension = e
            liveTabs.forEach { it.attachIconMessages() }
        }, { err -> Log.w("Antite", "icons extension failed", err) })
    }

    override val backgroundServices = listOf(
        BackgroundService(
            BackgroundService.SAFE_BROWSING, "Безопасный просмотр Google",
            "Списки опасных сайтов и загрузок, обновляются несколько раз в час",
            SAFE_BROWSING_HOSTS, defaultEnabled = false,
        ),
        BackgroundService(
            CONNECTIVITY, "Проверка подключения",
            "При запуске и смене сети: запрос success.txt по IPv4 и IPv6 и DNS-запрос example.org",
            listOf("firefox-portal-detection.com", "example.org"), defaultEnabled = true,
            offEffect = "Браузер не заметит страницу входа в сеть Wi-Fi (кафе, транспорт) и хуже определит, есть ли IPv6",
        ),
        BackgroundService(
            REMOTE_SETTINGS, "Списки Mozilla",
            "Раз в сутки: отозванные сертификаты (CRLite), промежуточные сертификаты, списки трекеров, " +
                "запрещённые расширения. По запросу: модели перевода",
            REMOTE_SETTINGS_HOSTS, defaultEnabled = true,
            offEffect = "Перестанут обновляться списки трекеров и отозванных сертификатов, не будет работать перевод страниц",
        ),
        BackgroundService(
            ADDON_UPDATES, "Обновления расширений",
            "Раз в сутки: версии установленных расширений, новая версия скачивается",
            listOf("versioncheck.addons.mozilla.org", "addons.mozilla.org"), defaultEnabled = true,
            offEffect = "uBlock Origin и другие расширения не будут обновляться. Списки фильтров uBlock обновляются отдельно",
        ),
        BackgroundService(
            OCSP, "Проверка сертификатов (OCSP)",
            "Для сайтов с EV-сертификатом: вопрос центру сертификации, не отозван ли сертификат. Центр видит, какой сайт открыт",
            listOf("OCSP-сервер центра сертификации сайта"), defaultEnabled = true,
            offEffect = "Отзыв EV-сертификатов проверяется только по спискам CRLite",
        ),
    )

    private fun writeConfig(context: Context): String {
        val f = File(context.filesDir, CONFIG)
        if (!f.exists()) f.writeText(BASE_CONFIG)
        return f.path
    }

    // Prefs also go to the config file: Gecko reads it at startup, before the first request.
    private fun saveStartupPrefs() {
        val text = buildString {
            append(BASE_CONFIG)
            for ((name, value) in startupPrefs) {
                append("  ").append(name).append(": ")
                if (value is String) append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                else append(value)
                append('\n')
            }
        }
        val f = File(context.filesDir, CONFIG)
        disk.execute {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        }
    }

    internal fun register(tab: GeckoTab) {
        liveTabs += tab
    }

    internal fun unregister(tab: GeckoTab) {
        liveTabs -= tab
    }

    override fun createTab(options: TabOptions, callbacks: TabCallbacks): EngineTab =
        GeckoTab(this, options, callbacks, open = true)

    override fun createView(context: Context): EngineView = GeckoEngineView(GeckoView(context))

    private val web by lazy { GeckoWebExecutor(runtime) }
    private val io = java.util.concurrent.Executors.newCachedThreadPool()

    override fun fetch(url: String, maxBytes: Int, done: (ByteArray?) -> Unit) {
        val result = try {
            web.fetch(WebRequest.Builder(url).build())
        } catch (e: Exception) {
            done(null)
            return
        }
        result.accept({ resp ->
            io.execute {
                val bytes = try {
                    val body = resp?.body
                    if (resp == null || resp.statusCode !in 200..299 || body == null) null
                    else body.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (out.size() <= maxBytes) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        if (out.size() > maxBytes) null else out.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                }
                main.post { done(bytes) }
            }
        }, { main.post { done(null) } })
    }

    override fun download(url: String, referrer: String?, privateMode: Boolean, done: (DownloadRequest?) -> Unit) {
        val result = try {
            val request = WebRequest.Builder(url).apply { if (referrer != null) referrer(referrer) }.build()
            web.fetch(request, if (privateMode) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE)
        } catch (e: Exception) {
            done(null)
            return
        }
        result.accept({ resp ->
            val body = resp?.body
            if (resp == null || resp.statusCode !in 200..299 || body == null) {
                runCatching { body?.close() }
                main.post { done(null) }
                return@accept
            }
            fun header(name: String) = resp.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            val r = DownloadRequest(resp.uri, header("content-disposition"), header("content-type"), body,
                header("content-length")?.trim()?.toLongOrNull() ?: -1)
            main.post { done(r) }
        }, { main.post { done(null) } })
    }

    override fun applySettings(settings: EngineSettings) {
        val s = runtime.settings
        s.setPreferredColorScheme(
            when (settings.colorScheme) {
                ColorScheme.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
                ColorScheme.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                ColorScheme.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
            },
        )
        s.setJavaScriptEnabled(true)
        s.setAutomaticFontSizeAdjustment(false)
        s.setFontSizeFactor(settings.fontScale)
        s.setForceUserScalableEnabled(settings.forceUserZoom)
        s.setDoubleTapZoomingEnabled(settings.doubleTapZoom)
        s.setGlobalPrivacyControl(settings.globalPrivacyControl)
        s.setAllowInsecureConnections(
            if (settings.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL,
        )
        s.setTranslationsOfferPopup(settings.offerTranslation)
        if (settings.locales.isNotEmpty()) s.setLocales(settings.locales.toTypedArray())

        val doh = settings.dohUrl
        if (settings.dohMode == DnsOverHttpsMode.OFF || doh == null) {
            s.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
        } else {
            s.setTrustedRecursiveResolverUri(doh)
            s.setTrustedRecursiveResolverMode(
                if (settings.dohMode == DnsOverHttpsMode.ONLY) GeckoRuntimeSettings.TRR_MODE_ONLY
                else GeckoRuntimeSettings.TRR_MODE_FIRST,
            )
        }

        val cb = s.contentBlocking
        when (settings.tracking) {
            TrackingProtection.STRICT -> {
                cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                cb.setAntiTracking(ContentBlocking.AntiTracking.STRICT)
                cb.setStrictSocialTrackingProtection(true)
            }
            TrackingProtection.OFF -> {
                cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
                cb.setAntiTracking(ContentBlocking.AntiTracking.NONE)
                cb.setStrictSocialTrackingProtection(false)
            }
            TrackingProtection.STANDARD -> {
                cb.setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                cb.setAntiTracking(ContentBlocking.AntiTracking.DEFAULT)
                cb.setStrictSocialTrackingProtection(false)
            }
        }
        val cookies = when (settings.cookies) {
            CookiePolicy.ISOLATE_THIRD_PARTY -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
            CookiePolicy.BLOCK_TRACKERS -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
            CookiePolicy.BLOCK_THIRD_PARTY -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            CookiePolicy.ALLOW_ALL -> ContentBlocking.CookieBehavior.ACCEPT_ALL
        }
        cb.setCookieBehavior(cookies)
        cb.setCookieBehaviorPrivateMode(cookies)
        cb.setQueryParameterStrippingEnabled(settings.stripTrackingParams)
        cb.setQueryParameterStrippingPrivateBrowsingEnabled(settings.stripTrackingParams)
        val off = settings.disabledServices
        val safeBrowsing = BackgroundService.SAFE_BROWSING !in off
        cb.setSafeBrowsing(if (safeBrowsing) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE)
        pref("browser.safebrowsing.malware.enabled", safeBrowsing)
        pref("browser.safebrowsing.phishing.enabled", safeBrowsing)
        pref("browser.safebrowsing.downloads.remote.enabled", safeBrowsing)
        pref("network.connectivity-service.enabled", CONNECTIVITY !in off)
        pref("extensions.update.enabled", ADDON_UPDATES !in off)
        pref("security.OCSP.enabled", if (OCSP in off) 0 else 2)
        // Remote settings ignore a custom server in release builds, so disabled hosts resolve to localhost.
        val sinkholed = (if (safeBrowsing) emptyList() else SAFE_BROWSING_HOSTS) +
            (if (REMOTE_SETTINGS in off) REMOTE_SETTINGS_HOSTS else emptyList())
        pref("network.dns.localDomains", sinkholed.joinToString(","))

        s.setFingerprintingProtection(settings.fingerprinting != FingerprintProtection.PRIVATE_ONLY)
        s.setFingerprintingProtectionPrivateBrowsing(true)
        pref("privacy.resistFingerprinting", settings.fingerprinting == FingerprintProtection.MAXIMUM)
        pref("privacy.donottrackheader.enabled", settings.doNotTrack)

        for (name in listOf(
            "network.prefetch-next", "network.predictor.enabled", "browser.places.speculativeConnect.enabled",
        )) pref(name, settings.preload)
        pref("network.dns.disablePrefetch", !settings.preload)
        pref("network.http.speculative-parallel-limit", if (settings.preload) 6 else 0)

        val rtc = settings.webRtc
        pref("media.peerconnection.enabled", rtc != WebRtcPolicy.DISABLED)
        val hide = rtc != WebRtcPolicy.DEFAULT
        pref("media.peerconnection.ice.default_address_only", hide)
        pref("media.peerconnection.ice.no_host", hide)
        pref("media.peerconnection.ice.proxy_only_if_behind_proxy", hide)
        pref("media.peerconnection.ice.proxy_only", rtc == WebRtcPolicy.PROXY_ONLY)

        pref("device.sensors.motion.enabled", settings.motionSensors)
        pref("device.sensors.orientation.enabled", settings.motionSensors)
        for ((perm, def) in settings.permissionDefaults) {
            val value = when (def) {
                PermissionDefault.ASK -> 0
                PermissionDefault.ALLOW -> 1
                PermissionDefault.BLOCK -> 2
            }
            for (type in geckoTypes(perm)) pref("permissions.default.$type", value)
        }

        s.setLoginAutofillEnabled(settings.autofillPasswords)
        pref("signon.rememberSignons", settings.savePasswords)
        pref("signon.generation.enabled", settings.savePasswords)
        pref("extensions.formautofill.available", "on")
        pref("extensions.formautofill.addresses.supported", if (settings.autofillAddresses) "on" else "off")
        pref("extensions.formautofill.addresses.capture.requiredFields", "street-address,address-level2")
        pref("extensions.formautofill.addresses.enabled", settings.autofillAddresses)
        pref("extensions.formautofill.addresses.capture.enabled", settings.autofillAddresses)
        pref("extensions.formautofill.creditCards.enabled", settings.autofillCards)
        saveStartupPrefs()
    }

    private fun pref(name: String, value: Boolean) {
        startupPrefs[name] = value
        GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
    }

    private fun pref(name: String, value: Int) {
        startupPrefs[name] = value
        GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
    }

    private fun pref(name: String, value: String) {
        startupPrefs[name] = value
        GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
    }

    override fun setAutofillStore(store: AutofillStore) {
        runtime.autocompleteStorageDelegate = GeckoAutofill.storage(store)
    }

    override fun setNotificationHandler(handler: NotificationHandler) {
        runtime.webNotificationDelegate = object : WebNotificationDelegate {
            override fun onShowNotification(n: WebNotification) {
                handler.onNotification(
                    WebNotificationEvent.Show(
                        id = "${n.origin}|${n.tag}",
                        title = n.title ?: "",
                        text = n.text ?: "",
                        origin = n.origin,
                        silent = n.silent,
                        click = { n.click() },
                        shown = { ok -> if (ok) n.show() else n.dismiss() },
                    ),
                )
            }

            override fun onCloseNotification(n: WebNotification) {
                handler.onNotification(WebNotificationEvent.Close("${n.origin}|${n.tag}"))
            }
        }
    }

    override val storage = object : StorageCleaner {
        override fun clear(what: Set<DataKind>, done: (Boolean) -> Unit) {
            var flags = 0L
            if (DataKind.CACHE in what) flags = flags or StorageController.ClearFlags.ALL_CACHES
            if (DataKind.COOKIES_AND_SITE_DATA in what) {
                flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.SITE_DATA or
                    StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS
            }
            if (DataKind.PERMISSIONS in what) {
                flags = flags or StorageController.ClearFlags.PERMISSIONS or StorageController.ClearFlags.SITE_SETTINGS
            }
            if (flags == 0L) {
                done(true)
                return
            }
            runtime.storageController.clearData(flags).accept({ done(true) }, { done(false) })
        }

        override fun clearSite(host: String, done: (Boolean) -> Unit) {
            runtime.storageController.clearDataFromBaseDomain(host, StorageController.ClearFlags.ALL)
                .accept({ done(true) }, { done(false) })
        }
    }

    override val permissions = object : PermissionStore {
        private val known = mutableMapOf<Pair<String, SitePermission>, ContentPermission>()

        override fun list(done: (List<PermissionEntry>) -> Unit) {
            runtime.storageController.allPermissions.accept({ list ->
                val out = mutableListOf<PermissionEntry>()
                for (p in list.orEmpty()) {
                    val kind = sitePermission(p.permission) ?: continue
                    known[p.uri to kind] = p
                    out += PermissionEntry(p.uri, kind, permissionValue(p.value), p.privateMode)
                }
                done(out)
            }, { done(emptyList()) })
        }

        override fun set(origin: String, permission: SitePermission, value: PermissionValue) {
            val raw = when (value) {
                PermissionValue.ALLOW -> ContentPermission.VALUE_ALLOW
                PermissionValue.BLOCK -> ContentPermission.VALUE_DENY
                PermissionValue.ASK -> 0
            }
            for (type in geckoTypes(permission)) {
                val p = make(origin, type) ?: continue
                runtime.storageController.setPermission(p, raw)
            }
        }

        override fun remove(entry: PermissionEntry) {
            val p = known[entry.origin to entry.permission] ?: return
            runtime.storageController.setPermission(p, 0)
        }
    }

    internal fun forgetLater(p: ContentPermission) {
        main.postDelayed({ runtime.storageController.setPermission(p, 0) }, 1500)
    }

    private fun make(origin: String, type: String): ContentPermission? = try {
        val base = origin.trimEnd('/')
        ContentPermission.fromJson(
            JSONObject()
                .put("uri", "$base/")
                .put("principal", JSONObject().put("1", JSONObject().put("0", "$base/")).toString())
                .put("privateMode", false)
                .put("perm", type)
                .put("value", ContentPermission.VALUE_PROMPT),
        )
    } catch (e: Exception) {
        null
    }

    private fun permissionValue(v: Int) = when (v) {
        ContentPermission.VALUE_ALLOW -> PermissionValue.ALLOW
        ContentPermission.VALUE_DENY -> PermissionValue.BLOCK
        else -> PermissionValue.ASK
    }

    override val extensions: Extensions = object : Extensions {
        private val installed = linkedMapOf<String, WebExtension>()
        private val actions = mutableMapOf<String, WebExtension.Action>()
        private var popupHandler: (((TabCallbacks) -> EngineTab) -> Unit)? = null
        private var changed: (() -> Unit)? = null

        init {
            val ctl = runtime.webExtensionController
            ctl.promptDelegate = object : WebExtensionController.PromptDelegate {
                override fun onInstallPromptRequest(
                    extension: WebExtension,
                    permissions: Array<String>,
                    origins: Array<String>,
                    dataCollectionPermissions: Array<String>,
                ): GeckoResult<WebExtension.PermissionPromptResponse> =
                    GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, false))
            }
            ctl.list().accept { list -> list.orEmpty().filter { !it.isBuiltIn }.forEach(::register) }
        }

        private fun register(e: WebExtension?) {
            if (e == null) return
            installed[e.id] = e
            e.setActionDelegate(object : WebExtension.ActionDelegate {
                override fun onBrowserAction(ext: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                    if (session == null) actions[ext.id] = action
                }

                override fun onTogglePopup(ext: WebExtension, action: WebExtension.Action) = openPopup()

                override fun onOpenPopup(ext: WebExtension, action: WebExtension.Action) = openPopup()
            })
            changed?.invoke()
        }

        private fun openPopup(): GeckoResult<GeckoSession>? {
            val handler = popupHandler ?: return null
            var tab: GeckoTab? = null
            handler { cb -> GeckoTab.popup(this@GeckoEngine, cb).also { tab = it } }
            return tab?.let { GeckoResult.fromValue(it.session) }
        }

        override fun list() = installed.values.map {
            Extension(it.id, it.metaData.name ?: it.id, it.metaData.optionsPageUrl, actions.containsKey(it.id))
        }

        override fun ensureInstalled(id: String, url: String) {
            runtime.webExtensionController.list().accept { list ->
                if (list.orEmpty().none { it.id == id }) runtime.webExtensionController.install(url).accept(::register)
            }
        }

        override fun clickAction(id: String) {
            actions[id]?.click()
        }

        override fun setTabActive(tab: EngineTab, active: Boolean) {
            runtime.webExtensionController.setTabActive((tab as GeckoTab).session, active)
        }

        override fun setPopupHandler(handler: ((TabCallbacks) -> EngineTab) -> Unit) {
            popupHandler = handler
        }

        override fun setChangeListener(listener: () -> Unit) {
            changed = listener
        }
    }

    override val translation: Translation = object : Translation {
        override fun supported(done: (List<Language>, List<Language>) -> Unit, failed: () -> Unit) {
            RuntimeTranslation.listSupportedLanguages().accept({ sup ->
                if (sup == null) {
                    failed()
                    return@accept
                }
                done(
                    sup.fromLanguages.orEmpty().map { Language(it.code, it.localizedDisplayName ?: it.code) },
                    sup.toLanguages.orEmpty().map { Language(it.code, it.localizedDisplayName ?: it.code) },
                )
            }, { failed() })
        }

        override fun models(done: (List<LanguageModel>) -> Unit, failed: () -> Unit) {
            RuntimeTranslation.listModelDownloadStates().accept({ list ->
                done(list.orEmpty().mapNotNull { m ->
                    val l = m.language ?: return@mapNotNull null
                    LanguageModel(Language(l.code, l.localizedDisplayName ?: l.code), m.isDownloaded == true, m.size)
                })
            }, { failed() })
        }

        private fun manage(code: String, op: String, done: (Boolean) -> Unit) {
            RuntimeTranslation.manageLanguageModel(
                RuntimeTranslation.ModelManagementOptions.Builder()
                    .languageToManage(code)
                    .operation(op)
                    .operationLevel(RuntimeTranslation.LANGUAGE)
                    .build(),
            ).accept({ done(true) }, { done(false) })
        }

        override fun downloadModel(code: String, done: (Boolean) -> Unit) = manage(code, RuntimeTranslation.DOWNLOAD, done)

        override fun deleteModel(code: String, done: (Boolean) -> Unit) = manage(code, RuntimeTranslation.DELETE, done)

        override fun neverTranslateSites(done: (List<String>) -> Unit) {
            RuntimeTranslation.getNeverTranslateSiteList().accept({ done(it.orEmpty()) }, { done(emptyList()) })
        }

        override fun allowTranslateSite(site: String, done: () -> Unit) {
            RuntimeTranslation.setNeverTranslateSpecifiedSite(false, site).accept({ done() }, { done() })
        }
    }
}

internal fun sitePermission(type: Int): SitePermission? = when (type) {
    PermissionDelegate.PERMISSION_GEOLOCATION -> SitePermission.LOCATION
    PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> SitePermission.NOTIFICATIONS
    PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> SitePermission.PERSISTENT_STORAGE
    PermissionDelegate.PERMISSION_XR -> SitePermission.XR
    PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> SitePermission.AUTOPLAY_AUDIBLE
    PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> SitePermission.AUTOPLAY_INAUDIBLE
    PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> SitePermission.DRM
    PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> SitePermission.LOCAL_NETWORK
    PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS -> SitePermission.LOCAL_DEVICE
    PermissionDelegate.PERMISSION_TRACKING -> SitePermission.TRACKING_EXCEPTION
    PermissionDelegate.PERMISSION_STORAGE_ACCESS -> SitePermission.STORAGE_ACCESS
    else -> null
}

internal fun geckoTypes(p: SitePermission): List<String> = when (p) {
    SitePermission.LOCATION -> listOf("geolocation")
    SitePermission.NOTIFICATIONS -> listOf("desktop-notification")
    SitePermission.PERSISTENT_STORAGE -> listOf("persistent-storage")
    SitePermission.XR -> listOf("xr")
    SitePermission.AUTOPLAY_AUDIBLE -> listOf("autoplay-media-audible")
    SitePermission.AUTOPLAY_INAUDIBLE -> listOf("autoplay-media-inaudible")
    SitePermission.DRM -> listOf("media-key-system-access")
    SitePermission.LOCAL_NETWORK -> listOf("local-network")
    SitePermission.LOCAL_DEVICE -> listOf("loopback-network")
    SitePermission.TRACKING_EXCEPTION -> listOf("trackingprotection")
    SitePermission.STORAGE_ACCESS -> emptyList()
}

internal class GeckoEngineView(private val gecko: GeckoView) : EngineView {
    override val view: View get() = gecko

    override fun show(tab: EngineTab?) {
        val session = (tab as GeckoTab?)?.session
        if (gecko.session === session) return
        gecko.releaseSession()
        if (session != null) gecko.setSession(session)
    }

    override fun capture(done: (Bitmap?) -> Unit) {
        try {
            gecko.capturePixels().accept({ done(it) }, { done(null) })
        } catch (e: Exception) {
            done(null)
        }
    }

    override fun setToolbarMaxHeight(px: Int) = gecko.setDynamicToolbarMaxHeight(px)

    override fun setVerticalClipping(px: Int) = gecko.setVerticalClipping(px)
}
