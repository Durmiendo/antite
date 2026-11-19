package dev.antite.browser.data

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.antite.browser.l10n.T
import dev.antite.engine.EngineTab

class Tab(val id: TabId, val incognito: Boolean) {
    companion object {
        private var nextId = System.currentTimeMillis()

        fun create(incognito: Boolean) = Tab(TabId(nextId++), incognito)
    }

    init {
        if (id.value >= nextId) nextId = id.value + 1
    }

    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var state: String? = null
    var thumb by mutableStateOf<Bitmap?>(null)
    var thumbRequested = false
    var parentId: TabId? = null
    var lastUsed = 0L
    var lastCrashAt = 0L
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var secure by mutableStateOf(false)
    var securityHost by mutableStateOf("")
    var issuer by mutableStateOf("")
    var httpsOnlyFailed by mutableStateOf<String?>(null)
    var desktop by mutableStateOf(false)
    // A user category id from Categories; null means only "All".
    var category by mutableStateOf<CategoryId?>(null)
    // A container id from Containers, fixed when the tab is made; null is the common storage.
    var container: ContainerId? = null
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

// The tab shown after the current one at index is closed (tabs no longer hold it): the one that opened it, else the
// nearest of the same kind before it, then after it; from incognito the last used normal tab when no incognito is left.
fun nextAfterClose(tabs: List<Tab>, closed: Tab, index: Int): Tab? {
    tabs.firstOrNull { it.id == closed.parentId && it.incognito == closed.incognito }?.let { return it }
    for (k in (minOf(index, tabs.size) - 1) downTo 0) if (tabs[k].incognito == closed.incognito) return tabs[k]
    for (k in index until tabs.size) if (tabs[k].incognito == closed.incognito) return tabs[k]
    return tabs.filter { !it.incognito }.maxByOrNull { it.lastUsed }
}
