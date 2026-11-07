package dev.antite.browser

import dev.antite.engine.AndroidPermissionRequest
import dev.antite.engine.ContentPermissionRequest
import dev.antite.engine.ContextInfo
import dev.antite.engine.DownloadRequest
import dev.antite.engine.EngineTab
import dev.antite.engine.LoadError
import dev.antite.engine.MediaEvent
import dev.antite.engine.MediaPermissionRequest
import dev.antite.engine.Prompt
import dev.antite.engine.SecurityInfo
import dev.antite.engine.TabCallbacks
import dev.antite.engine.TabOptions
import dev.antite.engine.TranslationState

open class NoopTabCallbacks : TabCallbacks {
    override fun onLocationChange(url: String) {}
    override fun errorPage(url: String, error: LoadError): String? = null
    override fun onTitleChange(title: String) {}
    override fun onPageStart(url: String) {}
    override fun onPageStop(success: Boolean) {}
    override fun onProgress(percent: Int) {}
    override fun onSecurityChange(info: SecurityInfo) {}
    override fun onNavigationState(canGoBack: Boolean, canGoForward: Boolean) {}
    override fun onStateChange(state: String) {}
    override fun onFirstPaint() {}
    override fun onScroll(y: Int) {}
    override fun onFullscreen(full: Boolean) {}
    override fun onFocusRequest() {}
    override fun onCloseRequest() {}
    override fun onCrash() {}
    override fun onNewTab(url: String, create: (TabOptions, TabCallbacks) -> EngineTab): EngineTab =
        create(TabOptions(privateMode = false, desktopMode = false, javascript = true), NoopTabCallbacks())
    override fun allowJavascript(url: String) = true
    override fun onVisit(url: String, title: String?) = false
    override fun isVisited(urls: List<String>, done: (BooleanArray) -> Unit) = done(BooleanArray(urls.size))
    override fun onContextMenu(info: ContextInfo) {}
    override fun onDownload(request: DownloadRequest) = request.body.close()
    override fun onPageMeta(pageUrl: String, iconUrl: String?, themeColor: Int?) {}
    override fun onPrompt(prompt: Prompt) = prompt.dismiss()
    override fun onContentPermission(request: ContentPermissionRequest) = request.deny()
    override fun onMediaPermission(request: MediaPermissionRequest) = request.reject()
    override fun onAndroidPermission(request: AndroidPermissionRequest) = request.denied()
    override fun onTranslationOffer() {}
    override fun onTranslationState(state: TranslationState) {}
    override fun onMedia(event: MediaEvent) {}
}
