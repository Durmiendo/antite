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

    fun applySettings(settings: EngineSettings)

    fun setAutofillStore(store: AutofillStore)

    fun setNotificationHandler(handler: NotificationHandler)

    fun createTab(options: TabOptions, callbacks: TabCallbacks): EngineTab

    fun createView(context: Context): EngineView
}

fun interface EngineFactory {
    fun create(context: Context): Engine
}

interface EngineView {
    val view: View

    fun show(tab: EngineTab?)

    fun capture(done: (Bitmap?) -> Unit)

    fun setToolbarMaxHeight(px: Int)

    fun setVerticalClipping(px: Int)
}

interface StorageCleaner {
    fun clear(what: Set<DataKind>, done: (Boolean) -> Unit)

    fun clearSite(host: String, done: (Boolean) -> Unit)
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
