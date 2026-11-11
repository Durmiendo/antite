package dev.antite.browser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.Browser
import dev.antite.browser.R
import dev.antite.browser.l10n.T
import kotlinx.coroutines.delay

// A settings page made only of rows, so search can walk it with the same code that draws it.
internal class SettingsPage(val title: String, val build: LazyListScope.(Nav) -> Unit)

@Composable
internal fun Screen(b: Browser, nav: Nav, page: SettingsPage) = Screen(b, nav, page.title) { page.build(this, nav) }

// A row that opens another settings page; search goes into that page too.
internal fun LazyListScope.page(b: Browser, nav: Nav, page: SettingsPage, summary: String?) {
    val key = "page:${page.title}"
    (this as? SettingsIndex)?.page(page, summary, key, nav)
    item(key = key) { Marked(key) { SettingRow(page.title, summary, onClick = { nav.push(page.title) { n -> Screen(b, n, page) } }) } }
}

// Lets search find a row that is drawn by hand; call it right before the row's item.
internal fun LazyListScope.mark(title: String, summary: String?, key: String, extra: String = "") {
    (this as? SettingsIndex)?.row(title, summary, key, extra)
}

// Where a search result was opened: its page scrolls to the row and the row lights up once.
internal class SettingTarget {
    var page by mutableStateOf<String?>(null)
    var key by mutableStateOf<String?>(null)
    var index by mutableIntStateOf(-1)

    fun clear() {
        page = null
        key = null
        index = -1
    }
}

internal val LocalSettingTarget = compositionLocalOf<SettingTarget?> { null }
internal val LocalSettingsPageTitle = compositionLocalOf<String?> { null }

@Composable
internal fun Marked(key: String, content: @Composable () -> Unit) {
    val target = LocalSettingTarget.current
    val here = target != null && target.key == key && target.page == LocalSettingsPageTitle.current
    val glow = remember { Animatable(0f) }
    if (here) LaunchedEffect(target) {
        glow.snapTo(1f)
        delay(400)
        glow.animateTo(0f, tween(1200))
        target.clear()
    }
    val color = LocalPalette.current.accent
    Box(Modifier.drawBehind { if (glow.value > 0f) drawRect(color.copy(alpha = 0.25f * glow.value)) }) { content() }
}

// Runs page builders without drawing them and remembers every row: its title, words to match and where it sits.
internal class SettingsIndex : LazyListScope {
    class Entry(
        val title: String, val summary: String?, val words: String, val path: List<SettingsPage>, val section: String?,
        val key: String, val index: Int, val opens: SettingsPage?,
    )

    val entries = mutableListOf<Entry>()
    private var path = emptyList<SettingsPage>()
    private var count = 0
    var section: String? = null

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        count++
    }

    override fun items(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, itemContent: @Composable LazyItemScope.(Int) -> Unit) {
        this.count += count
    }

    @Deprecated("Superseded by the overload with an index", level = DeprecationLevel.HIDDEN)
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        count++
    }

    fun row(title: String, summary: String?, key: String, extra: String = "", opens: SettingsPage? = null) {
        val words = listOfNotNull(title, summary, extra, section).joinToString(" ")
        entries += Entry(title, summary, words, path, section, key, count, opens)
    }

    fun page(page: SettingsPage, summary: String?, key: String, nav: Nav) {
        row(page.title, summary, key, opens = page)
        val saved = Triple(path, count, section)
        path = path + page
        count = 0
        section = null
        page.build(this, nav)
        path = saved.first
        count = saved.second
        section = saved.third
    }
}

private fun norm(s: String) = s.lowercase().replace('ё', 'е')

internal fun findSettings(index: List<SettingsIndex.Entry>, query: String): List<SettingsIndex.Entry> {
    val q = norm(query.trim())
    if (q.isEmpty()) return emptyList()
    val words = q.split(' ').filter { it.isNotEmpty() }
    return index.filter { e -> norm(e.words + " " + e.path.joinToString(" ") { it.title }).let { h -> words.all { it in h } } }
        .sortedBy { e ->
            val t = norm(e.title)
            when {
                t.startsWith(q) -> 0
                q in t -> 1
                words.all { it in t } -> 2
                else -> 3
            }
        }
}

private fun breadcrumb(e: SettingsIndex.Entry): String? {
    val parts = e.path.map { it.title } + listOfNotNull(e.section)
    return if (parts.isEmpty()) e.summary else parts.joinToString(" › ")
}

internal fun LazyListScope.settingsSearchField(query: MutableState<String>) = item(key = "search") {
    val p = LocalPalette.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp)
            .background(p.card, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(R.drawable.ic_search, size = 20)
            Box(Modifier.padding(start = 12.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    query.value, { query.value = it }, singleLine = true, cursorBrush = SolidColor(p.accent),
                    textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.value.isEmpty()) Text(T.settingsSearch, color = p.textSecondary, fontSize = 16.sp)
            }
            if (query.value.isNotEmpty()) IconBtn(R.drawable.ic_close, T.actionClear) { query.value = "" }
        }
    }
}

internal fun LazyListScope.settingsSearchResults(b: Browser, nav: Nav, found: List<SettingsIndex.Entry>, target: SettingTarget?) {
    if (found.isEmpty()) return note(T.settingsSearchEmpty)
    found.forEach { e ->
        item(key = "found:" + e.path.joinToString("/") { it.title } + "/" + e.key) {
            SettingRow(e.title, breadcrumb(e), onClick = {
                e.path.forEach { pg -> nav.push(pg.title) { n -> Screen(b, n, pg) } }
                val opens = e.opens
                if (opens != null) {
                    nav.push(opens.title) { n -> Screen(b, n, opens) }
                } else if (target != null && e.path.isNotEmpty()) {
                    target.page = e.path.last().title
                    target.key = e.key
                    target.index = e.index
                }
            })
        }
    }
}
