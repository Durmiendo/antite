package dev.antite.browser.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.Browser
import dev.antite.browser.LibraryKind
import dev.antite.browser.R
import dev.antite.browser.Urls

private sealed interface MenuEntry {
    class Item(@DrawableRes val icon: Int, val label: String, val enabled: Boolean = true, val action: () -> Unit) : MenuEntry
    class Check(@DrawableRes val icon: Int, val label: String, val checked: Boolean, val action: () -> Unit) : MenuEntry
    class Sub(@DrawableRes val icon: Int, val label: String, val action: () -> Unit) : MenuEntry
    data object Line : MenuEntry
}

private class TopIcon(@DrawableRes val icon: Int, val label: String, val enabled: Boolean, val action: () -> Unit)

@Composable
fun BrowserMenu(b: Browser) {
    val t = b.current ?: run { if (b.menuOpen) b.menuOpen = false; return }
    var more by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // The menu stays composed while closed, so opening it only draws; only MenuPopup reads menuOpen.
    LaunchedEffect(Unit) {
        snapshotFlow { b.menuOpen }.collect { open ->
            if (!open) {
                more = false
                scroll.scrollTo(0)
            }
        }
    }
    val web = Urls.isWeb(t.url)
    val close = { b.menuOpen = false }
    val open = { b.menuOpen }
    if (more) {
        MenuPopup(open, close, scroll, emptyList(), listOf(
            MenuEntry.Item(R.drawable.ic_picture_as_pdf, "Сохранить как PDF", web) { b.downloads.savePdf(t) },
            MenuEntry.Item(R.drawable.ic_link, "Копировать ссылку") { b.activity.copy(t.url) },
            MenuEntry.Item(R.drawable.ic_refresh, "Обновить без кэша") { b.ensureSession(t).reload(bypassCache = true) },
            MenuEntry.Item(R.drawable.ic_restore_page, "Недавние вкладки") { b.showLibrary(LibraryKind.CLOSED) },
        ))
        return
    }
    val bookmarked = web && t.bookmarked
    MenuPopup(open, close, scroll, listOf(
        TopIcon(R.drawable.ic_arrow_forward, "Вперёд", t.canGoForward) { b.ensureSession(t).goForward() },
        TopIcon(if (bookmarked) R.drawable.ic_star_fill1 else R.drawable.ic_star,
            if (bookmarked) "Удалить из закладок" else "Добавить в закладки", web) { b.toggleBookmark(t) },
        TopIcon(R.drawable.ic_download, "Сохранить как PDF", web) { b.downloads.savePdf(t) },
        TopIcon(R.drawable.ic_info, "Сведения о сайте", true) { b.pageInfo() },
        TopIcon(if (t.loading) R.drawable.ic_close else R.drawable.ic_refresh, if (t.loading) "Остановить" else "Обновить", true) {
            val s = b.ensureSession(t)
            if (t.loading) s.stop() else s.reload()
        },
    ), listOf(
        MenuEntry.Item(R.drawable.ic_add_box, "Новая вкладка") { b.newTabAndFocus(false) },
        MenuEntry.Item(R.drawable.ic_incognito, "Новая вкладка инкогнито") { b.newTabAndFocus(true) },
        MenuEntry.Line,
        MenuEntry.Item(R.drawable.ic_history, "История") { b.showLibrary(LibraryKind.HISTORY) },
        MenuEntry.Item(R.drawable.ic_delete, "Удалить данные о работе в браузере") { b.clearDataDialog() },
        MenuEntry.Line,
        MenuEntry.Item(R.drawable.ic_download_done, "Скачанные файлы") { b.showLibrary(LibraryKind.DOWNLOADS) },
        MenuEntry.Item(R.drawable.ic_star, "Закладки") { b.showLibrary(LibraryKind.BOOKMARKS) },
        MenuEntry.Item(R.drawable.ic_folder, "Добавить все вкладки в закладки") { b.bookmarkAllTabs() },
        MenuEntry.Item(R.drawable.ic_devices, "Недавние вкладки") { b.showLibrary(LibraryKind.CLOSED) },
        MenuEntry.Item(R.drawable.ic_extension, "Открыть меню расширений") { b.openExtensionsMenu() },
        MenuEntry.Line,
        MenuEntry.Item(R.drawable.ic_share, "Поделиться…") { b.activity.share(t.url, t.title) },
        MenuEntry.Item(R.drawable.ic_find_in_page, "Найти на странице") { b.showFind() },
        MenuEntry.Item(R.drawable.ic_translate, if (t.translated) "Показать оригинал" else "Перевести…") { b.translate(t) },
        MenuEntry.Sub(R.drawable.ic_home_repair_service, "Ещё инструменты") { more = true },
        MenuEntry.Item(R.drawable.ic_install_desktop, "Установить и создать ярлык") { b.activity.addShortcut(t, b.icons.get(Urls.host(t.url))) },
        MenuEntry.Item(R.drawable.ic_description, "View source") {
            if (t.url.isNotEmpty() && !t.url.startsWith("view-source:")) b.openTab("view-source:" + t.url, t.incognito, t, true)
        },
        MenuEntry.Check(R.drawable.ic_desktop_windows, "Версия для ПК", t.desktop) { b.toggleDesktop(t) },
        MenuEntry.Check(R.drawable.ic_brightness_6, "Тёмная тема", b.prefs.isDark()) { b.toggleDark() },
        MenuEntry.Line,
        MenuEntry.Item(R.drawable.ic_settings, "Настройки") { b.showSettings() },
        MenuEntry.Item(R.drawable.ic_exit_to_app, "Exit") { b.exitApp() },
    ))
}

@Composable
private fun MenuPopup(open: () -> Boolean, close: () -> Unit, scroll: ScrollState, top: List<TopIcon>, entries: List<MenuEntry>) {
    val isOpen = open()
    // Drawn in the page window: a Popup window took a new window and focus on every opening.
    // Placement alone does not refresh accessibility, so the semantics switch with it.
    Box(Modifier.fillMaxSize().layout { m, c ->
        val placeable = m.measure(c)
        layout(placeable.width, placeable.height) { if (isOpen) placeable.place(0, 0) }
    }.then(if (isOpen) Modifier else Modifier.clearAndSetSemantics {})) {
        MenuBody(close, scroll, top, entries)
    }
}

@Composable
private fun BoxScope.MenuBody(close: () -> Unit, scroll: ScrollState, top: List<TopIcon>, entries: List<MenuEntry>) {
    val p = LocalPalette.current
    val width = minOf(LocalConfiguration.current.screenWidthDp - 34, 420).dp
    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); close() } })
    Column(
        Modifier.align(Alignment.TopEnd).offset { IntOffset(-16, 12) }.width(width).heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).dp)
            .shadow(8.dp, RoundedCornerShape(20.dp)).background(p.menu, RoundedCornerShape(20.dp))
            .verticalScroll(scroll).padding(vertical = 8.dp),
    ) {
        if (top.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
                top.forEach { ic ->
                    Box(Modifier.weight(1f).height(56.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(44.dp).background(if (ic.enabled) p.iconCircle else p.iconCircle.copy(alpha = 0.4f), CircleShape)
                                .clickable(enabled = ic.enabled) { close(); ic.action() },
                            contentAlignment = Alignment.Center,
                        ) { Ico(ic.icon, if (ic.enabled) p.text else p.iconDisabled) }
                    }
                }
            }
        }
        entries.forEach { e ->
            when (e) {
                MenuEntry.Line -> Divider()
                is MenuEntry.Item -> MenuRow(e.icon, e.label, e.enabled, { close(); e.action() })
                is MenuEntry.Sub -> MenuRow(e.icon, e.label, true, e.action) { Ico(R.drawable.ic_chevron_right) }
                is MenuEntry.Check -> MenuRow(e.icon, e.label, true, { close(); e.action() }) {
                    Checkbox(e.checked, null, colors = CheckboxDefaults.colors(
                        checkedColor = p.accent, checkmarkColor = p.onAccent, uncheckedColor = p.icon))
                }
            }
        }
    }
}

@Composable
private fun MenuRow(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(50.dp).clickable(enabled = enabled, onClick = onClick).padding(start = 20.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon, if (enabled) p.icon else p.iconDisabled)
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (enabled) p.text else p.textDisabled, fontSize = 16.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
