package dev.antite.browser.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.FormField
import dev.antite.browser.LibraryKind
import dev.antite.browser.R
import dev.antite.browser.UiDialog
import dev.antite.browser.Urls
import dev.antite.browser.data.Db
import dev.antite.browser.data.Entry
import dev.antite.browser.l10n.T
import dev.antite.browser.restoreClosed
import dev.antite.browser.service.Downloads
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun ago(time: Long): String {
    if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) return T.timeJustNow
    return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

@Composable
fun LibraryScreen(b: Browser, shown: Boolean) {
    val p = LocalPalette.current
    val kind = b.libraryKind
    var filter by remember { mutableStateOf("") }
    // Each list is kept from its last load, so a page opens with it at once and only refreshes it.
    val lists = remember { mutableStateMapOf<LibraryKind, List<Entry>>() }
    var found by remember { mutableStateOf<Pair<LibraryKind, List<Entry>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var closes by remember { mutableIntStateOf(0) }
    val downloadsRevision = b.downloads.revision.intValue
    suspend fun load(k: LibraryKind, f: String) = b.db.io {
        when (k) {
            LibraryKind.HISTORY -> history(f, 2000)
            LibraryKind.BOOKMARKS -> bookmarks(f)
            LibraryKind.CLOSED -> closed(f)
            LibraryKind.DOWNLOADS -> downloads(f)
        }
    }
    LaunchedEffect(Unit) {
        for (k in LibraryKind.entries) if (k !in lists) lists[k] = load(k, "")
    }
    LaunchedEffect(kind, filter, reload, downloadsRevision, shown) {
        if (!shown && kind in lists) return@LaunchedEffect
        if (filter.isEmpty()) {
            lists[kind] = load(kind, "")
        } else {
            // Typing in the search box: one query when the typing pauses, not one per letter.
            delay(150)
            found = kind to load(kind, filter)
        }
    }
    LaunchedEffect(shown) {
        if (shown) return@LaunchedEffect
        delay(Motion.closeMs().toLong())
        filter = ""
        found = null
        closes++
    }
    val items = if (filter.isEmpty()) lists[kind].orEmpty() else found?.takeIf { it.first == kind }?.second.orEmpty()
    val loaded = if (filter.isEmpty()) kind in lists else found?.first == kind
    fun change(write: Db.() -> Unit) {
        b.activity.lifecycleScope.launch {
            b.db.io(write)
            reload++
        }
    }

    val title = when (kind) {
        LibraryKind.HISTORY -> T.menuHistory
        LibraryKind.BOOKMARKS -> T.menuBookmarks
        LibraryKind.CLOSED -> T.menuRecentTabs
        LibraryKind.DOWNLOADS -> T.menuDownloads
    }
    val empty = when (kind) {
        LibraryKind.HISTORY -> T.libraryHistoryEmpty
        LibraryKind.BOOKMARKS -> T.libraryBookmarksEmpty
        LibraryKind.CLOSED -> T.libraryClosedEmpty
        LibraryKind.DOWNLOADS -> T.libraryDownloadsEmpty
    }
    val icon = when (kind) {
        LibraryKind.HISTORY -> R.drawable.ic_history
        LibraryKind.BOOKMARKS -> R.drawable.ic_star
        LibraryKind.CLOSED -> R.drawable.ic_tab
        LibraryKind.DOWNLOADS -> R.drawable.ic_download_done
    }

    fun open(e: Entry, newTab: Boolean) {
        when (kind) {
            LibraryKind.CLOSED -> b.restoreClosed(e.id, e.url ?: return, e.title, e.extra)
            LibraryKind.DOWNLOADS -> b.downloads.open(e)
            else -> {
                b.overlay = dev.antite.browser.Overlay.NONE
                b.load(e.url ?: return, newTab)
            }
        }
    }

    fun actions(e: Entry) {
        val name = e.title?.ifEmpty { null } ?: e.url ?: ""
        val list = when (kind) {
            LibraryKind.HISTORY -> listOf(
                DialogButton(T.openInNewTab) { open(e, true) },
                DialogButton(T.actionCopyLink) { b.activity.copy(e.url ?: "") },
                DialogButton(T.libraryDeleteFromHistory) { change { deleteHistory(e.id) } },
            )
            LibraryKind.BOOKMARKS -> listOf(
                DialogButton(T.openInNewTab) { open(e, true) },
                DialogButton(T.actionRename) {
                    b.show(UiDialog.Form(T.libraryBookmarkName, null, listOf(FormField(T.ntpTileName, e.title ?: "")),
                        onConfirm = { v -> change { renameBookmark(e.id, v[0].trim()) }; true }))
                },
                DialogButton(T.actionCopyLink) { b.activity.copy(e.url ?: "") },
                DialogButton(T.actionDelete) { change { deleteBookmark(e.id) } },
            )
            LibraryKind.CLOSED -> listOf(
                DialogButton(T.actionRestore) { open(e, false) },
                DialogButton(T.actionCopyLink) { b.activity.copy(e.url ?: "") },
                DialogButton(T.libraryRemoveFromList) { change { deleteClosed(e.id) } },
            )
            LibraryKind.DOWNLOADS -> if (Downloads.loading(e)) listOf(
                DialogButton(T.libraryCancelDownload) { b.downloads.cancel(e) },
            ) else listOf(
                DialogButton(T.actionOpen) { b.downloads.open(e) },
                DialogButton(T.actionShare) { b.downloads.share(e) },
                DialogButton(T.libraryDropFromList) { change { deleteDownload(e.id) } },
            )
        }
        b.show(UiDialog.Items(name, list))
    }

    fun clearAll() = when (kind) {
        LibraryKind.HISTORY -> b.confirm(T.libraryClearHistory, T.actionDelete) { change { clearHistory() } }
        LibraryKind.CLOSED -> change { clearClosed() }
        LibraryKind.DOWNLOADS -> b.confirm(T.libraryClearDownloads, T.actionClear) {
            change { clearDownloads() }
        }
        LibraryKind.BOOKMARKS -> {}
    }

    CompositionLocalProvider(LocalPageReset provides closes) {
        PageScaffold(title, { b.overlay = dev.antite.browser.Overlay.NONE }, actions = {
            if (kind != LibraryKind.BOOKMARKS) TextButton(onClick = ::clearAll) { Text(T.actionClear, color = p.accent) }
        }) {
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(44.dp)
                        .background(p.pill, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(R.drawable.ic_search, size = 20)
                    BasicTextField(filter, { filter = it }, singleLine = true, cursorBrush = SolidColor(p.accent),
                        textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (filter.isEmpty()) Text(T.librarySearch, color = p.textSecondary, fontSize = 16.sp)
                                inner()
                            }
                        })
                }
            }
            if (loaded && items.isEmpty()) note(empty)
            if (kind == LibraryKind.DOWNLOADS && b.prefs.int("dlLayout", 0) == 1) {
                downloadBlocks(b, items, { open(it, false) }, ::actions)
                return@PageScaffold
            }
            items(items, key = { it.id }) { e ->
                val host = Urls.host(e.url)
                val sub = when (kind) {
                    LibraryKind.HISTORY -> "$host · ${ago(e.time)}"
                    LibraryKind.BOOKMARKS -> e.url
                    LibraryKind.CLOSED -> T.libraryClosedAt(host = host, ago = ago(e.time))
                    LibraryKind.DOWNLOADS -> "${Downloads.status(e)} · ${ago(e.time)}"
                }
                SettingRow(
                    e.title?.ifEmpty { null } ?: e.url ?: "", sub,
                    onClick = { open(e, false) }, onLongClick = { actions(e) },
                    leading = {
                        if (kind == LibraryKind.DOWNLOADS) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            MediaThumb(b, e, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), 80) { Ico(icon) }
                        } else {
                            val bmp = b.icons.get(host)
                            if (bmp != null) SiteIcon(bmp, host, 24) else Ico(icon)
                        }
                    },
                )
            }
        }
    }
}
