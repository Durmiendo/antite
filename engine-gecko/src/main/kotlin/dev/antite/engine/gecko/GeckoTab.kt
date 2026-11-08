package dev.antite.engine.gecko

import android.net.Uri
import android.util.Base64
import dev.antite.engine.AndroidPermissionRequest
import dev.antite.engine.ContentPermissionRequest
import dev.antite.engine.ContextInfo
import dev.antite.engine.DownloadRequest
import dev.antite.engine.EngineTab
import dev.antite.engine.LoadError
import dev.antite.engine.MediaController
import dev.antite.engine.MediaEvent
import dev.antite.engine.MediaMetadata
import dev.antite.engine.MediaPermissionRequest
import dev.antite.engine.Prompt
import dev.antite.engine.SecurityInfo
import dev.antite.engine.TabCallbacks
import dev.antite.engine.TabOptions
import dev.antite.engine.TranslationState
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.TranslationsController.SessionTranslation
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.InputStream
import java.util.WeakHashMap

internal class GeckoTab(
    private val engine: GeckoEngine,
    options: TabOptions,
    private val callbacks: TabCallbacks,
    open: Boolean,
) : EngineTab {
    val session: GeckoSession

    init {
        val b = GeckoSessionSettings.Builder()
            .usePrivateMode(options.privateMode)
            .allowJavascript(options.javascript)
        if (options.desktopMode) {
            b.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
        }
        session = GeckoSession(b.build())
        attach()
        engine.register(this)
        if (open) {
            session.open(engine.runtime)
            val state = options.state
            val restored = state != null && try {
                val parsed = GeckoSession.SessionState.fromString(state)
                if (parsed != null) session.restoreState(parsed)
                parsed != null
            } catch (e: Exception) {
                false
            }
            if (!restored) session.loadUri(options.url?.takeIf { it.isNotEmpty() } ?: "about:blank")
        }
    }

    companion object {
        fun popup(engine: GeckoEngine, callbacks: TabCallbacks): GeckoTab = GeckoTab(
            engine,
            TabOptions(privateMode = false, desktopMode = false, javascript = true),
            callbacks,
            open = true,
        )

        private val controllers = WeakHashMap<MediaSession, MediaController>()

        private fun controller(ms: MediaSession): MediaController = controllers.getOrPut(ms) {
            object : MediaController {
                override fun play() = ms.play()
                override fun pause() = ms.pause()
                override fun stop() = ms.stop()
                override fun next() = ms.nextTrack()
                override fun previous() = ms.previousTrack()
                override fun seekTo(seconds: Double) = ms.seekTo(seconds, false)
            }
        }
    }

    override var desktopMode: Boolean = options.desktopMode
        set(value) {
            field = value
            session.settings.userAgentMode =
                if (value) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            session.settings.viewportMode =
                if (value) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }

    override var javascript: Boolean
        get() = session.settings.allowJavascript
        set(value) {
            if (session.settings.allowJavascript != value) session.settings.allowJavascript = value
        }

    override fun load(url: String) = session.loadUri(url)

    override fun reload(bypassCache: Boolean) =
        if (bypassCache) session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE) else session.reload()

    override fun stop() = session.stop()

    override fun goBack() = session.goBack()

    override fun goForward() = session.goForward()

    override fun setActive(active: Boolean) {
        session.setActive(active)
        engine.runtime.webExtensionController.setTabActive(session, active)
    }

    override fun exitFullscreen() = session.exitFullScreen()

    override fun purgeHistory() = session.purgeHistory()

    override fun find(text: String?, backwards: Boolean, result: (Int, Int) -> Unit) {
        session.finder.find(text, if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0).accept { r ->
            if (r != null) result(r.current, r.total)
        }
    }

    override fun clearFind() = session.finder.clear()

    override fun saveAsPdf(done: (InputStream?) -> Unit) {
        session.saveAsPdf().accept({ done(it) }, { done(null) })
    }

    override fun translate(from: String, to: String, done: (String?) -> Unit) {
        val st = session.sessionTranslation
        if (st == null) {
            done("unsupported")
            return
        }
        st.translate(from, to, SessionTranslation.TranslationOptions.Builder().downloadModel(true).build())
            .accept({ done(null) }, { done(it?.message ?: "error") })
    }

    override fun restoreOriginal(done: () -> Unit) {
        session.sessionTranslation?.restoreOriginalPage()?.accept({ done() }, { done() }) ?: done()
    }

    override fun close() {
        engine.unregister(this)
        session.close()
    }

    fun attachIconMessages() {
        val ext = engine.iconsExtension ?: return
        if (session.settings.usePrivateMode) return
        session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onMessage(app: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                val m = message as? JSONObject ?: return null
                val page = m.optString("page")
                if (Uri.parse(page).host == null) return null
                val th: JSONArray? = m.optJSONArray("theme")
                val color = if (th == null || th.length() < 3) null
                else android.graphics.Color.rgb(th.optInt(0), th.optInt(1), th.optInt(2))
                callbacks.onPageMeta(page, m.optString("icon").ifEmpty { null }, color)
                return null
            }
        }, "antite")
    }

    private fun attach() {
        attachIconMessages()
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            private var back = false
            private var forward = false

            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest):
                GeckoResult<AllowOrDeny>? {
                javascript = callbacks.allowJavascript(request.uri)
                return null
            }

            override fun onLocationChange(
                s: GeckoSession,
                url: String?,
                perms: List<ContentPermission>,
                hasUserGesture: Boolean,
            ) = callbacks.onLocationChange(url ?: "")

            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                val kind = when {
                    error.code == WebRequestError.ERROR_UNKNOWN_HOST -> LoadError.HOST_NOT_FOUND
                    error.code == WebRequestError.ERROR_CONNECTION_REFUSED -> LoadError.CONNECTION
                    error.code == WebRequestError.ERROR_NET_TIMEOUT -> LoadError.TIMEOUT
                    error.code == WebRequestError.ERROR_OFFLINE -> LoadError.OFFLINE
                    error.category == WebRequestError.ERROR_CATEGORY_SECURITY -> LoadError.SECURITY
                    error.category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> LoadError.BLOCKED
                    else -> LoadError.OTHER
                }
                val html = callbacks.errorPage(uri ?: "", kind) ?: return null
                return GeckoResult.fromValue(
                    "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP),
                )
            }

            override fun onCanGoBack(s: GeckoSession, can: Boolean) {
                back = can
                callbacks.onNavigationState(back, forward)
            }

            override fun onCanGoForward(s: GeckoSession, can: Boolean) {
                forward = can
                callbacks.onNavigationState(back, forward)
            }

            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                val child = callbacks.onNewTab(uri) { opts, cb -> GeckoTab(engine, opts, cb, open = false) }
                return GeckoResult.fromValue((child as GeckoTab).session)
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) = callbacks.onPageStart(url)

            override fun onPageStop(s: GeckoSession, success: Boolean) = callbacks.onPageStop(success)

            override fun onProgressChange(s: GeckoSession, progress: Int) = callbacks.onProgress(progress)

            override fun onSecurityChange(s: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) =
                callbacks.onSecurityChange(
                    SecurityInfo(
                        info.isSecure,
                        info.host ?: "",
                        info.certificate?.issuerX500Principal?.name ?: "",
                    ),
                )

            override fun onSessionStateChange(s: GeckoSession, state: GeckoSession.SessionState) =
                callbacks.onStateChange(state.toString())
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) = callbacks.onTitleChange(title ?: "")

            override fun onCloseRequest(s: GeckoSession) = callbacks.onCloseRequest()

            override fun onFocusRequest(s: GeckoSession) = callbacks.onFocusRequest()

            override fun onFirstContentfulPaint(s: GeckoSession) = callbacks.onFirstPaint()

            override fun onFullScreen(s: GeckoSession, full: Boolean) = callbacks.onFullscreen(full)

            override fun onContextMenu(
                s: GeckoSession,
                x: Int,
                y: Int,
                el: GeckoSession.ContentDelegate.ContextElement,
            ) = callbacks.onContextMenu(
                ContextInfo(
                    el.linkUri,
                    el.linkText,
                    el.srcUri,
                    el.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE,
                ),
            )

            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                val body = response.body ?: return
                callbacks.onDownload(
                    DownloadRequest(response.uri, header(response, "content-disposition"),
                        header(response, "content-type"), body,
                        header(response, "content-length")?.trim()?.toLongOrNull() ?: -1),
                )
            }

            override fun onCrash(s: GeckoSession) = callbacks.onCrash()

            override fun onKill(s: GeckoSession) = callbacks.onCrash()
        }
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(s: GeckoSession, x: Int, y: Int) = callbacks.onScroll(y)
        }
        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onVisited(s: GeckoSession, url: String, last: String?, flags: Int): GeckoResult<Boolean> {
                val skip = (flags and GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL) == 0 ||
                    (flags and GeckoSession.HistoryDelegate.VISIT_UNRECOVERABLE_ERROR) != 0 ||
                    (flags and (GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE or
                        GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT)) != 0
                return GeckoResult.fromValue(!skip && callbacks.onVisit(url, null))
            }

            override fun getVisited(s: GeckoSession, urls: Array<String>): GeckoResult<BooleanArray> {
                val r = GeckoResult<BooleanArray>()
                callbacks.isVisited(urls.toList()) { r.complete(it) }
                return r
            }
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                s: GeckoSession,
                perms: Array<String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) = callbacks.onAndroidPermission(
                AndroidPermissionRequest(perms.orEmpty().toList()) { ok ->
                    if (ok) callback.grant() else callback.reject()
                },
            )

            override fun onMediaPermissionRequest(
                s: GeckoSession,
                uri: String,
                video: Array<MediaSource>?,
                audio: Array<MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                val cam = video?.firstOrNull { it.source == MediaSource.SOURCE_CAMERA }
                val mic = audio?.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE }
                if (cam == null && mic == null) {
                    callback.reject()
                    return
                }
                callbacks.onMediaPermission(
                    MediaPermissionRequest(uri, cam != null, mic != null,
                        { c, m -> callback.grant(if (c) cam else null, if (m) mic else null) },
                        { callback.reject() }),
                )
            }

            override fun onContentPermissionRequest(s: GeckoSession, perm: ContentPermission): GeckoResult<Int> {
                val kind = sitePermission(perm.permission)
                    ?: return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
                val r = GeckoResult<Int>()
                callbacks.onContentPermission(
                    ContentPermissionRequest(perm.uri, kind, perm.privateMode,
                        { ok -> r.complete(if (ok) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY) },
                        { engine.forgetLater(perm) }),
                )
                return r
            }
        }
        session.translationsSessionDelegate = object : SessionTranslation.Delegate {
            override fun onOfferTranslate(s: GeckoSession) = callbacks.onTranslationOffer()

            override fun onTranslationStateChange(s: GeckoSession, state: SessionTranslation.TranslationState?) {
                if (state == null) return
                callbacks.onTranslationState(
                    TranslationState(
                        state.detectedLanguages?.docLangTag,
                        state.requestedTranslationPair != null,
                        state.error,
                    ),
                )
            }
        }
        session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(s: GeckoSession, ms: MediaSession) =
                callbacks.onMedia(MediaEvent.Activated(controller(ms)))

            override fun onDeactivated(s: GeckoSession, ms: MediaSession) =
                callbacks.onMedia(MediaEvent.Deactivated(controller(ms)))

            override fun onMetadata(s: GeckoSession, ms: MediaSession, meta: MediaSession.Metadata) {
                val c = controller(ms)
                callbacks.onMedia(MediaEvent.Metadata(c, MediaMetadata(meta.title, meta.artist)))
                meta.artwork?.getBitmap(256)?.accept({ b ->
                    if (b != null) callbacks.onMedia(MediaEvent.Artwork(c, b))
                }, { })
            }

            override fun onPlay(s: GeckoSession, ms: MediaSession) = callbacks.onMedia(MediaEvent.Playing(controller(ms)))

            override fun onPause(s: GeckoSession, ms: MediaSession) = callbacks.onMedia(MediaEvent.Paused(controller(ms)))

            override fun onStop(s: GeckoSession, ms: MediaSession) = callbacks.onMedia(MediaEvent.Stopped(controller(ms)))

            override fun onFullscreen(s: GeckoSession, ms: MediaSession, full: Boolean, meta: MediaSession.ElementMetadata?) =
                callbacks.onMedia(MediaEvent.Fullscreen(controller(ms), full, meta?.width ?: 0, meta?.height ?: 0))
        }
        session.promptDelegate = GeckoPrompts(engine.context, callbacks)
    }

    private fun header(r: WebResponse, name: String): String? =
        r.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
