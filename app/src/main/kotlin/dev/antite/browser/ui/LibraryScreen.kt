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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.antite.browser.service.Downloads
import kotlinx.coroutines.launch

fun ago(time: Long): String {
    if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) return "только что"
    return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

@Composable
fun LibraryScreen(b: Browser) {
    val p = LocalPalette.current
    val kind = b.libraryKind
    var filter by remember(kind) { mutableStateOf("") }
    var items by remember(kind) { mutableStateOf(emptyList<Entry>()) }
    var loaded by remember(kind) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val downloadsRevision = b.downloads.revision.intValue
    LaunchedEffect(kind, filter, reload, downloadsRevision) {
        items = b.db.io {
            when (kind) {
                LibraryKind.HISTORY -> history(filter, 2000)
                LibraryKind.BOOKMARKS -> bookmarks(filter)
                LibraryKind.CLOSED -> closed(filter)
                LibraryKind.DOWNLOADS -> downloads(filter)
            }
        }
        loaded = true
    }
    fun change(write: Db.() -> Unit) {
        b.activity.lifecycleScope.launch {
            b.db.io(write)
            reload++
        }
    }

    val title = when (kind) {
        LibraryKind.HISTORY -> "История"
        LibraryKind.BOOKMARKS -> "Закладки"
        LibraryKind.CLOSED -> "Недавние вкладки"
        LibraryKind.DOWNLOADS -> "Скачанные файлы"
    }
    val empty = when (kind) {
        LibraryKind.HISTORY -> "История пуста"
        LibraryKind.BOOKMARKS -> "Закладок пока нет. Добавить можно звездой в меню."
        LibraryKind.CLOSED -> "Недавно закрытых вкладок нет"
        LibraryKind.DOWNLOADS -> "Загрузок пока нет"
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
                DialogButton("Открыть в новой вкладке") { open(e, true) },
                DialogButton("Копировать ссылку") { b.activity.copy(e.url ?: "") },
                DialogButton("Удалить из истории") { change { deleteHistory(e.id) } },
            )
            LibraryKind.BOOKMARKS -> listOf(
                DialogButton("Открыть в новой вкладке") { open(e, true) },
                DialogButton("Переименовать") {
                    b.show(UiDialog.Form("Название закладки", null, listOf(FormField("Название", e.title ?: "")),
                        onConfirm = { v -> change { renameBookmark(e.id, v[0].trim()) }; true }))
                },
                DialogButton("Копировать ссылку") { b.activity.copy(e.url ?: "") },
                DialogButton("Удалить") { change { deleteBookmark(e.id) } },
            )
            LibraryKind.CLOSED -> listOf(
                DialogButton("Восстановить") { open(e, false) },
                DialogButton("Копировать ссылку") { b.activity.copy(e.url ?: "") },
                DialogButton("Удалить из списка") { change { deleteClosed(e.id) } },
            )
            LibraryKind.DOWNLOADS -> if (Downloads.loading(e)) listOf(
                DialogButton("Отменить загрузку") { b.downloads.cancel(e) },
            ) else listOf(
                DialogButton("Открыть") { b.downloads.open(e) },
                DialogButton("Поделиться") { b.downloads.share(e) },
                DialogButton("Убрать из списка") { change { deleteDownload(e.id) } },
            )
        }
        b.show(UiDialog.Items(name, list))
    }

    fun clearAll() = when (kind) {
        LibraryKind.HISTORY -> b.confirm("Удалить всю историю просмотров?", "Удалить") { change { clearHistory() } }
        LibraryKind.CLOSED -> change { clearClosed() }
        LibraryKind.DOWNLOADS -> b.confirm("Очистить список загрузок? Сами файлы останутся в папке Download.", "Очистить") {
            change { clearDownloads() }
        }
        LibraryKind.BOOKMARKS -> {}
    }

    PageScaffold(title, { b.overlay = dev.antite.browser.Overlay.NONE }, actions = {
        if (kind != LibraryKind.BOOKMARKS) TextButton(onClick = ::clearAll) { Text("Очистить", color = p.accent) }
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
                            if (filter.isEmpty()) Text("Поиск", color = p.textSecondary, fontSize = 16.sp)
                            inner()
                        }
                    })
            }
        }
        if (loaded && items.isEmpty()) note(empty)
        items(items, key = { it.id }) { e ->
            val host = Urls.host(e.url)
            val sub = when (kind) {
                LibraryKind.HISTORY -> "$host · ${ago(e.time)}"
                LibraryKind.BOOKMARKS -> e.url
                LibraryKind.CLOSED -> "$host · закрыта ${ago(e.time)}"
                LibraryKind.DOWNLOADS -> "${Downloads.status(e)} · ${ago(e.time)}"
            }
            SettingRow(
                e.title?.ifEmpty { null } ?: e.url ?: "", sub,
                onClick = { open(e, false) }, onLongClick = { actions(e) },
                leading = {
                    if (kind == LibraryKind.DOWNLOADS) Ico(icon) else {
                        val bmp = b.icons.get(host)
                        if (bmp != null) SiteIcon(bmp, host, 24) else Ico(icon)
                    }
                },
            )
        }
    }
}
