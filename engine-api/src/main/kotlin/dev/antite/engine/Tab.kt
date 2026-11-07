package dev.antite.engine

import android.graphics.Bitmap
import java.io.InputStream

data class TabOptions(
    val privateMode: Boolean,
    val desktopMode: Boolean,
    val javascript: Boolean,
    val state: String? = null,
    val url: String? = null,
)

interface EngineTab {
    var desktopMode: Boolean
    var javascript: Boolean

    fun load(url: String)

    fun reload(bypassCache: Boolean = false)

    fun stop()

    fun goBack()

    fun goForward()

    fun setActive(active: Boolean)

    fun exitFullscreen()

    fun purgeHistory()

    fun find(text: String?, backwards: Boolean, result: (current: Int, total: Int) -> Unit)

    fun clearFind()

    fun saveAsPdf(done: (InputStream?) -> Unit)

    fun translate(from: String, to: String, done: (error: String?) -> Unit)

    fun restoreOriginal(done: () -> Unit)

    fun close()
}

data class SecurityInfo(val secure: Boolean, val host: String, val issuer: String)

data class ContextInfo(
    val linkUrl: String?,
    val linkText: String?,
    val srcUrl: String?,
    val isImage: Boolean,
)

class DownloadRequest(
    val url: String,
    val contentDisposition: String?,
    val contentType: String?,
    val body: InputStream,
)

data class TranslationState(
    val pageLanguage: String?,
    val translated: Boolean,
    val error: String?,
)

data class MediaMetadata(val title: String?, val artist: String?)

interface MediaController {
    fun play()

    fun pause()

    fun stop()

    fun next()

    fun previous()

    fun seekTo(seconds: Double)
}

sealed interface MediaEvent {
    data class Activated(val controller: MediaController) : MediaEvent
    data class Deactivated(val controller: MediaController) : MediaEvent
    data class Metadata(val controller: MediaController, val metadata: MediaMetadata) : MediaEvent
    data class Artwork(val controller: MediaController, val image: Bitmap) : MediaEvent
    data class Playing(val controller: MediaController) : MediaEvent
    data class Paused(val controller: MediaController) : MediaEvent
    data class Stopped(val controller: MediaController) : MediaEvent
}

interface TabCallbacks {
    fun onLocationChange(url: String)

    fun onTitleChange(title: String)

    fun onPageStart(url: String)

    fun onPageStop(success: Boolean)

    fun onProgress(percent: Int)

    fun onSecurityChange(info: SecurityInfo)

    fun onNavigationState(canGoBack: Boolean, canGoForward: Boolean)

    fun onStateChange(state: String)

    fun onFirstPaint()

    fun onScroll(y: Int)

    fun onFullscreen(full: Boolean)

    fun onFocusRequest()

    fun onCloseRequest()

    fun onCrash()

    fun onNewTab(url: String, create: (TabOptions, TabCallbacks) -> EngineTab): EngineTab

    fun allowJavascript(url: String): Boolean

    fun onVisit(url: String, title: String?): Boolean

    fun isVisited(urls: List<String>, done: (BooleanArray) -> Unit)

    fun onContextMenu(info: ContextInfo)

    fun onDownload(request: DownloadRequest)

    fun onPageMeta(pageUrl: String, iconUrl: String?, themeColor: Int?)

    fun onPrompt(prompt: Prompt)

    fun onContentPermission(request: ContentPermissionRequest)

    fun onMediaPermission(request: MediaPermissionRequest)

    fun onAndroidPermission(request: AndroidPermissionRequest)

    fun onTranslationOffer()

    fun onTranslationState(state: TranslationState)

    fun onMedia(event: MediaEvent)
}
