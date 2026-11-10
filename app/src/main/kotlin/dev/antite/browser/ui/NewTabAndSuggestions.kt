package dev.antite.browser.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.FormField
import dev.antite.browser.R
import dev.antite.browser.UiDialog
import dev.antite.browser.Urls
import dev.antite.browser.data.Entry
import dev.antite.browser.data.Options
import dev.antite.browser.data.Site
import dev.antite.browser.l10n.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import org.json.JSONArray

@Composable
fun SiteIcon(bitmap: Bitmap?, host: String, size: Int) {
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, Modifier.size(size.dp).clip(CircleShape))
        return
    }
    val hue = (host.hashCode() % 360).let { if (it < 0) -it else it }.toFloat()
    Box(
        Modifier.size(size.dp).background(Color.hsv(hue, 0.45f, 0.75f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(host.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 2).sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewTabPage(b: Browser, modifier: Modifier) {
    val p = LocalPalette.current
    var pins by remember { mutableStateOf(emptyList<Entry>()) }
    var frequent by remember { mutableStateOf(emptyList<Site>()) }
    val hidden = b.prefs.stringSet("hiddenHosts")
    LaunchedEffect(b.ntpRevision, hidden) {
        if (b.ntpRevision > 0) delay(150)
        val count = Options.frequentSites[b.prefs.index("ntpFrequent", Options.frequentSites, 3)].value
        val (pi, fr) = b.db.io {
            val list = pins()
            list to frequent(hidden + list.map { Urls.host(it.url) }, count)
        }
        pins = pi
        frequent = fr
    }
    fun change(write: dev.antite.browser.data.Db.() -> Unit) {
        b.activity.lifecycleScope.launch {
            b.db.io(write)
            b.ntpRevision++
        }
    }

    fun editPin(pin: Entry?) = b.show(UiDialog.Form(
        if (pin == null) T.ntpTileNew else T.ntpTileEdit, null,
        listOf(FormField(T.ntpTileName, pin?.title ?: "", FormField.Kind.NAME),
            FormField(T.ntpTileAddress, pin?.url ?: "", FormField.Kind.URL)),
        onConfirm = { v ->
            var u = v[1].trim()
            if (u.isEmpty()) {
                b.toast(T.ntpTileNeedAddress)
                return@Form false
            }
            if ("://" !in u) u = "https://$u"
            val label = v[0].trim().ifEmpty { Urls.host(u) }
            val target = u
            change { if (pin == null) addPin(target, label) else updatePin(pin.id, target, label) }
            true
        },
    ))

    BoxWithConstraints(modifier) {
        val cols = (maxWidth.value / 88).toInt().coerceIn(4, 8)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp)) {
            Header(T.ntpPinned)
            TileGrid(cols, pins.map { pin ->
                TileData(pin.title ?: Urls.host(pin.url), pin.url ?: "", { b.load(pin.url ?: return@TileData, false) }, menu = {
                    b.show(UiDialog.Items(pin.title?.ifEmpty { null } ?: Urls.host(pin.url), listOf(
                        DialogButton(T.openInNewTab) { b.load(pin.url ?: "", true) },
                        DialogButton(T.actionEdit) { editPin(pin) },
                        DialogButton(T.ntpMoveLeft) { change { movePin(pin.id, -1) } },
                        DialogButton(T.ntpMoveRight) { change { movePin(pin.id, 1) } },
                        DialogButton(T.ntpUnpin) { change { deletePin(pin.id) } },
                    )))
                })
            } + TileData(T.actionAdd, "", { editPin(null) }, null, add = true), b)
            if (frequent.isNotEmpty()) {
                Header(T.ntpFrequent)
                TileGrid(cols, frequent.map { s ->
                    TileData(s.host, s.url, { b.load(s.url, false) }, menu = {
                        b.show(UiDialog.Items(s.host, listOf(
                            DialogButton(T.openInNewTab) { b.load(s.url, true) },
                            DialogButton(T.ntpPin) { change { addPin(s.url, s.host) } },
                            DialogButton(T.ntpRemoveFrequent) {
                                b.prefs.edit { putStringSet("hiddenHosts", b.prefs.stringSet("hiddenHosts") + s.host) }
                            },
                        )))
                    })
                }, b)
            }
        }
    }
}

private class TileData(val label: String, val url: String, val open: () -> Unit, val menu: (() -> Unit)?, val add: Boolean = false)

@Composable
private fun Header(text: String) {
    Text(text, color = LocalPalette.current.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileGrid(cols: Int, tiles: List<TileData>, b: Browser) {
    val p = LocalPalette.current
    tiles.chunked(cols).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { tile ->
                Column(
                    Modifier.weight(1f).combinedClickable(onClick = tile.open, onLongClick = tile.menu).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(56.dp).background(p.card, CircleShape), contentAlignment = Alignment.Center) {
                        if (tile.add) Ico(R.drawable.ic_add) else SiteIcon(b.icons.get(Urls.host(tile.url)), Urls.host(tile.url), 36)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(tile.label, color = p.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
fun Suggestions(b: Browser, modifier: Modifier) {
    val p = LocalPalette.current
    val text = b.suggestQuery.trim()
    val incognito = b.current?.incognito == true
    var local by remember { mutableStateOf(emptyList<Entry>()) }
    var remote by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(text) {
        remote = emptyList()
        if (text.isEmpty()) {
            local = emptyList()
            return@LaunchedEffect
        }
        local = if (incognito) emptyList() else b.db.io { suggest(text, 5) }
        if (!b.prefs.bool("searchSuggest", true)) return@LaunchedEffect
        delay(150)
        b.engine.fetch(b.prefs.suggestUrl + Uri.encode(text), 64 * 1024) { bytes ->
            if (bytes == null || b.suggestQuery.trim() != text) return@fetch
            remote = runCatching {
                val items = JSONArray(String(bytes)).getJSONArray(1)
                (0 until minOf(items.length(), 6)).map { items.getString(it) }
            }.getOrDefault(emptyList())
        }
    }
    if (text.isEmpty()) return
    val looksUrl = (' ' !in text && '.' in text && !text.endsWith(".")) || "://" in text
    LazyColumn(modifier) {
        item {
            SuggestRow(if (looksUrl) R.drawable.ic_public else R.drawable.ic_search, null, text,
                if (looksUrl) T.suggestGo else T.suggestSearch, { b.navigate(text) }, null)
        }
        items(local) { e ->
            SuggestRow(if (e.extra == "b") R.drawable.ic_star else R.drawable.ic_history, e.url,
                e.title?.ifEmpty { null } ?: e.url ?: "", e.url, { b.navigate(e.url ?: "") }, { b.suggestFill = e.url })
        }
        items(remote) { s -> SuggestRow(R.drawable.ic_search, null, s, null, { b.navigate(s) }, { b.suggestFill = s }) }
    }
}

@Composable
private fun SuggestRow(icon: Int, url: String?, title: String, sub: String?, go: () -> Unit, fill: (() -> Unit)?) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = go).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val host = url?.let { Urls.host(it) }
        if (host != null) {
            val b = LocalBrowser.current
            val bmp = b?.icons?.get(host)
            if (bmp != null) SiteIcon(bmp, host, 24) else Ico(icon)
        } else Ico(icon)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = p.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (fill != null) {
            androidx.compose.material3.IconButton(onClick = fill) {
                Ico(R.drawable.ic_arrow_back, modifier = Modifier.rotate(45f))
            }
        }
    }
}

val LocalBrowser = androidx.compose.runtime.staticCompositionLocalOf<Browser?> { null }
