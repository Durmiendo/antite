package dev.antite.engine

import android.content.Context
import android.graphics.Bitmap
import android.view.View

enum class Capability {
    EXTENSIONS,
    AUTOFILL,
    TRANSLATION,
    SITE_PERMISSIONS,
    WEB_NOTIFICATIONS,
    SAVE_AS_PDF,
    MEDIA_SESSION,
}

data class EngineInfo(val type: String, val version: String)

interface Engine {
    val info: EngineInfo
    val capabilities: Set<Capability>
    val permissions: PermissionStore
    val storage: StorageCleaner
    val extensions: Extensions?
    val translation: Translation?
    val backgroundServices: List<BackgroundService>

    fun applySettings(settings: EngineSettings)

    fun setAutofillStore(store: AutofillStore)

    fun setNotificationHandler(handler: NotificationHandler)

    fun createTab(options: TabOptions, callbacks: TabCallbacks): EngineTab

    fun createView(context: Context): EngineView

    fun fetch(url: String, maxBytes: Int, done: (ByteArray?) -> Unit)

    // Done with null when the server does not answer 2xx.
    fun download(url: String, referrer: String?, privateMode: Boolean, done: (DownloadRequest?) -> Unit)
}

fun interface EngineFactory {
    fun create(context: Context): Engine
}

interface EngineView {
    val view: View

    fun show(tab: EngineTab?)

    // ARGB colour laid over the view each time show changes the tab, until the new tab draws itself: the picture of
    // the tab shown before never stays on screen, a tab of a locked category included.
    fun setCover(argb: Int) {}

    fun capture(done: (Bitmap?) -> Unit)

    fun setToolbarMaxHeight(px: Int)

    fun setVerticalClipping(px: Int)

    // The page is covered (a full-screen page of the browser, a lock): nothing of it may reach accessibility services.
    fun setAccessibilityHidden(hidden: Boolean) {}

    // Whether the touch in progress may pull the page down to refresh: the page did not take it for itself and
    // nothing under the finger, an inner scrolling box included, can scroll up. False while the engine has not answered
    // for this touch yet; null when it cannot tell at all.
    fun canPullToRefresh(): Boolean? = null
}

interface StorageCleaner {
    fun clear(what: Set<DataKind>, done: (Boolean) -> Unit)

    fun clearSite(host: String, done: (Boolean) -> Unit)

    fun clearContainer(id: String, done: (Boolean) -> Unit)
}

enum class DataKind { CACHE, COOKIES_AND_SITE_DATA, PERMISSIONS }

fun interface NotificationHandler {
    fun onNotification(event: WebNotificationEvent)
}

sealed interface WebNotificationEvent {
    data class Show(
        val id: String,
        val title: String,
        val text: String,
        val origin: String?,
        val silent: Boolean,
        val click: () -> Unit,
        val shown: (Boolean) -> Unit,
    ) : WebNotificationEvent

    data class Close(val id: String) : WebNotificationEvent
}
