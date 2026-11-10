package dev.antite.browser.data

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.antite.browser.l10n.T
import dev.antite.engine.EngineTab

class Tab(val id: Long, val incognito: Boolean) {
    companion object {
        private var nextId = System.currentTimeMillis()

        fun create(incognito: Boolean) = Tab(nextId++, incognito)
    }

    init {
        if (id >= nextId) nextId = id + 1
    }

    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var state: String? = null
    var thumb by mutableStateOf<Bitmap?>(null)
    var thumbRequested = false
    var parentId = 0L
    var lastUsed = 0L
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var secure by mutableStateOf(false)
    var securityHost by mutableStateOf("")
    var issuer by mutableStateOf("")
    var desktop by mutableStateOf(false)
    var docLang: String? = null
    var translated by mutableStateOf(false)
    var bookmarked by mutableStateOf(false)
    var engineTab: EngineTab? = null

    val live: Boolean get() = engineTab != null

    val label: String
        get() = when {
            title.isNotEmpty() -> title
            url.isNotEmpty() && url != "about:blank" -> url
            else -> T.tabNew
        }
}
