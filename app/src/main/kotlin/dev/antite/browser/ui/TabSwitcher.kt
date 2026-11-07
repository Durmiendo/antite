package dev.antite.browser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.Browser
import dev.antite.browser.R
import dev.antite.browser.Urls
import dev.antite.browser.data.Tab
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun TabSwitcher(b: Browser) {
    val p = LocalPalette.current
    val inc = b.switcherIncognito
    val shown = b.tabs.filter { it.incognito == inc }
    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.background(p.pill, RoundedCornerShape(20.dp)).padding(4.dp)) {
                val normal = b.incognitoCount(false)
                Box(
                    Modifier.height(32.dp).background(if (!inc) p.accent else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable { b.switcherIncognito = false }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (normal >= 100) ":D" else normal.toString(), color = if (!inc) p.onAccent else p.icon,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Box(
                    Modifier.width(52.dp).height(32.dp).background(if (inc) p.accent else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable { b.switcherIncognito = true },
                    contentAlignment = Alignment.Center,
                ) { Ico(R.drawable.ic_incognito, if (inc) p.onAccent else p.icon) }
            }
            Spacer(Modifier.weight(1f))
            IconBtn(R.drawable.ic_add, "Новая вкладка") {
                b.overlay = dev.antite.browser.Overlay.NONE
                b.newTabAndFocus(inc)
            }
        }
        if (b.prefs.int("tabLayout", 0) == 1) TabList(b, shown) else TabGrid(b, shown)
    }
}

private fun Browser.reorder(shown: List<Tab>, from: Int, to: Int) {
    if (from !in shown.indices || to !in shown.indices || from == to) return
    val moving = shown[from]
    val target = shown[to]
    val targetIndex = tabs.indexOf(target)
    moveTab(moving, if (from < to) targetIndex else targetIndex)
}

private fun Browser.select(t: Tab) {
    overlay = dev.antite.browser.Overlay.NONE
    switchTo(t)
}

@Composable
private fun Dismissible(b: Browser, t: Tab, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) b.closeTab(t)
    }
    SwipeToDismissBox(state, backgroundContent = {}) { content() }
}

@Composable
private fun TabGrid(b: Browser, shown: List<Tab>) {
    val p = LocalPalette.current
    val gridState = rememberLazyGridState(maxOf(0, shown.indexOf(b.current)))
    val reorder = rememberReorderableLazyGridState(gridState) { from, to -> b.reorder(shown, from.index, to.index) }
    LazyVerticalGrid(GridCells.Fixed(2), state = gridState, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.id }) { t ->
            ReorderableItem(reorder, key = t.id) { dragging ->
                Dismissible(b, t) {
                    val selected = t === b.current
                    Column(
                        Modifier.padding(6.dp).aspectRatio(0.8f).longPressDraggableHandle()
                            .clip(RoundedCornerShape(16.dp)).background(p.card)
                            .then(if (selected) Modifier.border(3.dp, p.accent, RoundedCornerShape(16.dp)) else Modifier)
                            .clickable { b.select(t) },
                    ) {
                        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val icon = b.icons.get(Urls.host(t.url))
                            if (icon != null) SiteIcon(icon, Urls.host(t.url), 16) else Ico(R.drawable.ic_public, size = 16)
                            Text(t.label, color = p.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Box(Modifier.size(36.dp).clickable { b.closeTab(t) }, contentAlignment = Alignment.Center) {
                                Ico(R.drawable.ic_close, size = 18)
                            }
                        }
                        Thumb(b, t, Modifier.fillMaxSize().padding(start = if (selected) 3.dp else 0.dp,
                            end = if (selected) 3.dp else 0.dp, bottom = if (selected) 3.dp else 0.dp).background(p.page))
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(b: Browser, t: Tab, modifier: Modifier) {
    LaunchedEffect(t.id) { b.loadThumb(t) }
    val bmp = t.thumb
    Box(modifier) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TabList(b: Browser, shown: List<Tab>) {
    val p = LocalPalette.current
    val listState = rememberLazyListState(maxOf(0, shown.indexOf(b.current)))
    val reorder = rememberReorderableLazyListState(listState) { from, to -> b.reorder(shown, from.index, to.index) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.id }) { t ->
            ReorderableItem(reorder, key = t.id) { _ ->
                Dismissible(b, t) {
                    val raw = if (t.incognito) 0 else b.icons.color(Urls.host(t.url))
                    val color = if (raw == 0) (if (t.incognito) p.toolbarIncognito else p.card) else Color(raw)
                    val light = color.luminance() > 0.55f
                    val text = if (light) Color(0xDE000000) else Color.White
                    val sub = if (light) Color(0x8A000000) else Color(0xB3FFFFFF)
                    Box(Modifier.fillMaxWidth().height(124.dp).longPressDraggableHandle().background(color).clickable { b.select(t) }) {
                        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 40.dp, top = 12.dp, bottom = 12.dp)) {
                            Thumb(b, t, Modifier.weight(0.42f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White))
                            Column(Modifier.weight(0.58f).fillMaxHeight().padding(start = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                                Text(t.label, color = text, fontSize = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(android.net.Uri.parse(t.url).host ?: "", color = sub, fontSize = 14.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        Box(Modifier.align(Alignment.TopEnd).size(40.dp).clickable { b.closeTab(t) }, contentAlignment = Alignment.Center) {
                            Ico(R.drawable.ic_close, sub)
                        }
                        if (t === b.current) Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().background(p.accent))
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Color(0x33000000)))
                    }
                }
            }
        }
    }
}
