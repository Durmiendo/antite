package dev.antite.browser.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import dev.antite.browser.data.Categories
import dev.antite.browser.data.TabCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.Browser
import dev.antite.browser.R
import dev.antite.browser.Urls
import dev.antite.browser.data.Tab
import dev.antite.browser.l10n.T
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

private class SwitcherPage(val key: String, val label: String, val category: TabCategory?)

// A card dragged out of the grid onto a strip chip moves its tab to that category; "All" takes the category away.
private class ChipDrop {
    val chips = mutableStateMapOf<String, Rect>()
    var dragging by mutableStateOf<Tab?>(null)
    // In window coordinates, like the chips; followed only while a card is dragged.
    var finger by mutableStateOf(Offset.Unspecified)

    val target: String?
        get() {
            val t = dragging ?: return null
            if (t.incognito || !finger.isSpecified) return null
            val key = chips.entries.firstOrNull { it.value.contains(finger) }?.key ?: return null
            return key.takeIf { it != Categories.INCOGNITO && it != (t.category ?: Categories.ALL) }
        }

    fun finish(b: Browser) {
        val t = dragging
        val key = target
        dragging = null
        finger = Offset.Unspecified
        if (t == null || key == null) return
        val c = b.categories.byId(key)
        b.moveToCategory(t, c?.id)
        b.toast(if (c != null) T.tabsMovedTo(name = c.name) else T.tabsMovedOut)
    }
}

@Composable
fun TabSwitcher(b: Browser, open: Boolean) {
    val p = LocalPalette.current
    val pages = buildList {
        add(SwitcherPage(Categories.ALL, T.tabsAll, null))
        b.categories.list.forEach { add(SwitcherPage(it.id, it.name, it)) }
        add(SwitcherPage(Categories.INCOGNITO, T.tabsIncognito, null))
    }
    val keys by rememberUpdatedState(pages.map { it.key })
    val index = keys.indexOf(b.switcherPage).coerceAtLeast(0)
    val pager = rememberPagerState(index) { keys.size }
    // The strip and Browser.switcherPage lead; a swipe that settles on another page reports back.
    LaunchedEffect(index, open) {
        if (pager.currentPage != index) if (open) pager.animateScrollToPage(index) else pager.scrollToPage(index)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { i -> keys.getOrNull(i)?.let { if (it != b.switcherPage) b.switcherPage = it } }
    }
    LaunchedEffect(b.switcherPage, open) {
        if (open && b.switcherPage == Categories.INCOGNITO && b.incognitoLocked) b.unlockIncognito()
        if (open) b.categories.byId(b.switcherPage)?.let(b::unlockCategory)
    }
    fun count(key: String): Int? = when {
        key == Categories.INCOGNITO && b.incognitoLocked || b.categoryLocked(key) -> null
        key == Categories.ALL -> b.tabs.count { !it.incognito && !b.tabLocked(it) }
        key == Categories.INCOGNITO -> b.tabs.count { it.incognito }
        else -> b.tabs.count { !it.incognito && it.category == key }
    }
    val drop = remember { ChipDrop() }
    val root = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Column(Modifier.fillMaxSize().background(p.background).onGloballyPositioned { root[0] = it }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val at = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()?.position ?: continue
                    if (drop.dragging != null) root[0]?.takeIf { it.isAttached }?.let { drop.finger = it.localToWindow(at) }
                }
            }
        }) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val strip = rememberLazyListState()
            LaunchedEffect(index) { strip.animateScrollToItem(maxOf(0, index - 1)) }
            val each = b.prefs.bool("tabCountEach", false)
            LazyRow(Modifier.weight(1f), strip, contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(pages, key = { _, pg -> pg.key }) { i, pg ->
                    CategoryChip(b, pg, i == index, if (each) count(pg.key) else null, drop) { b.switcherPage = pg.key }
                }
            }
            if (b.prefs.bool("tabCountTotal", true)) {
                val n = b.tabs.count { !b.tabLocked(it) }
                Text(if (n >= 100) ":D" else n.toString(), color = p.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp))
            }
            IconBtn(R.drawable.ic_add, T.tabNew) {
                val key = b.switcherPage
                b.overlay = dev.antite.browser.Overlay.NONE
                b.newTabAndFocus(key == Categories.INCOGNITO, key.takeIf { it != Categories.ALL && it != Categories.INCOGNITO })
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { keys.getOrElse(it) { "" } }) { i ->
            val key = keys.getOrElse(i) { Categories.ALL }
            val shown = when (key) {
                Categories.ALL -> b.tabs.filter { !it.incognito && !b.categoryLocked(it.category) }
                Categories.INCOGNITO -> b.tabs.filter { it.incognito }
                else -> b.tabs.filter { !it.incognito && it.category == key }
            }
            val lockedCategory = b.categories.byId(key)?.takeIf { b.categoryLocked(it.id) }
            when {
                key == Categories.INCOGNITO && b.incognitoLocked -> IncognitoLock(b, Modifier.fillMaxSize(), showTabs = false)
                lockedCategory != null -> CategoryLock(b, lockedCategory, Modifier.fillMaxSize(), showTabs = false)
                shown.isEmpty() -> Centered { Text(T.tabsEmpty, color = p.textSecondary, fontSize = 15.sp) }
                b.prefs.int("tabLayout", 0) == 1 -> TabList(b, shown, open, drop)
                else -> TabGrid(b, shown, open, drop)
            }
        }
    }
}

@Composable
private fun CategoryChip(b: Browser, pg: SwitcherPage, selected: Boolean, count: Int?, drop: ChipDrop, onClick: () -> Unit) {
    val p = LocalPalette.current
    val aimed = drop.target == pg.key
    DisposableEffect(pg.key) { onDispose { drop.chips.remove(pg.key) } }
    val fill = when {
        aimed -> p.accent.copy(alpha = 0.3f)
        !selected -> Color.Transparent
        pg.category != null -> Color(pg.category.color)
        pg.key == Categories.INCOGNITO -> p.toolbarIncognito
        else -> p.accent
    }
    val text = if (!selected || aimed) p.text else if (fill.luminance() > 0.5f) Color(0xDE000000) else Color.White
    Row(
        Modifier.height(34.dp).onGloballyPositioned { drop.chips[pg.key] = it.boundsInWindow() }
            .clip(RoundedCornerShape(17.dp)).background(fill)
            .then(if (aimed) Modifier.border(2.dp, p.accent, RoundedCornerShape(17.dp))
                else if (selected) Modifier else Modifier.border(1.dp, p.divider, RoundedCornerShape(17.dp)))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            pg.category != null -> CategoryIcon(b, pg.category, 18, Modifier.padding(end = 6.dp))
            pg.key == Categories.INCOGNITO -> Ico(R.drawable.ic_incognito, text, 18, Modifier.padding(end = 6.dp))
        }
        Text(pg.label, color = text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        if (count != null) {
            Text(if (count >= 100) ":D" else count.toString(), color = text.copy(alpha = 0.7f), fontSize = 13.sp,
                modifier = Modifier.padding(start = 6.dp))
        }
    }
}

// The category's picture, if it has one; decoded once and kept.
@Composable
internal fun CategoryIcon(b: Browser, c: TabCategory, size: Int, modifier: Modifier = Modifier) {
    val file = b.categories.iconFile(c) ?: return
    val bmp by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { b.categories.bitmap(file)?.asImageBitmap() }
    }
    bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = modifier.size(size.dp).clip(RoundedCornerShape(4.dp))) }
}

// Shown instead of incognito tabs while they are locked: in the switcher and over an incognito page.
@Composable
internal fun IncognitoLock(b: Browser, modifier: Modifier, showTabs: Boolean) =
    TabsLock(b, modifier, R.drawable.ic_incognito, T.incognitoLocked, showTabs) { b.unlockIncognito() }

// The same for a category with "unlock to enter".
@Composable
internal fun CategoryLock(b: Browser, c: TabCategory, modifier: Modifier, showTabs: Boolean) =
    TabsLock(b, modifier, R.drawable.ic_shield_lock, T.categoryLocked(name = c.name), showTabs) { b.unlockCategory(c) }

@Composable
private fun TabsLock(b: Browser, modifier: Modifier, icon: Int, title: String, showTabs: Boolean, unlock: () -> Unit) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Ico(icon, size = 48)
        Text(title, color = p.text, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp))
        Text(T.incognitoUnlock, color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(p.accent).clickable(onClick = unlock)
                .padding(horizontal = 24.dp, vertical = 10.dp))
        if (showTabs) {
            Text(T.incognitoShowTabs, color = p.accent, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).clickable { b.showSwitcher() }
                    .padding(horizontal = 24.dp, vertical = 10.dp))
        }
    }
}

private fun Browser.reorder(shown: List<Tab>, from: Int, to: Int) {
    if (from !in shown.indices || to !in shown.indices || from == to) return
    val moving = shown[from]
    val target = shown[to]
    val targetIndex = tabs.indexOf(target)
    moveTab(moving, targetIndex)
}

private fun Browser.select(t: Tab) {
    overlay = dev.antite.browser.Overlay.NONE
    switchTo(t)
}

@Composable
private fun TabGrid(b: Browser, shown: List<Tab>, open: Boolean, drop: ChipDrop) {
    val p = LocalPalette.current
    val gridState = rememberLazyGridState(maxOf(0, shown.indexOf(b.current)))
    // Kept composed between openings, so each opening starts at the current tab again.
    LaunchedEffect(open) { if (open) gridState.requestScrollToItem(maxOf(0, shown.indexOf(b.current))) }
    val reorder = rememberReorderableLazyGridState(gridState) { from, to -> b.reorder(shown, from.index, to.index) }
    LazyVerticalGrid(GridCells.Fixed(2), state = gridState, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.id }) { t ->
            ReorderableItem(reorder, key = t.id) { dragging ->
                run {
                    val selected = t === b.current
                    val card = remember { arrayOfNulls<LayoutCoordinates>(1) }
                    Column(
                        Modifier.padding(6.dp).aspectRatio(0.8f)
                            .longPressDraggableHandle(onDragStarted = { drop.dragging = t }, onDragStopped = { drop.finish(b) })
                            .onGloballyPositioned { card[0] = it }
                            .clip(RoundedCornerShape(16.dp)).background(p.card)
                            .then(if (selected) Modifier.border(3.dp, p.accent, RoundedCornerShape(16.dp)) else Modifier)
                            .clickable { b.pickedCard = card[0]; b.select(t) },
                    ) {
                        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val icon = b.icons.get(Urls.host(t.url))
                            if (icon != null) SiteIcon(icon, Urls.host(t.url), 16) else Ico(R.drawable.ic_public, size = 16)
                            Text(t.label, color = p.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Box(Modifier.size(36.dp).clickable(onClickLabel = T.tabClose) { b.closeTab(t) }
                                .semantics { contentDescription = T.tabClose }, contentAlignment = Alignment.Center) {
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
private fun TabList(b: Browser, shown: List<Tab>, open: Boolean, drop: ChipDrop) {
    val p = LocalPalette.current
    val listState = rememberLazyListState(maxOf(0, shown.indexOf(b.current)))
    LaunchedEffect(open) { if (open) listState.requestScrollToItem(maxOf(0, shown.indexOf(b.current))) }
    val reorder = rememberReorderableLazyListState(listState) { from, to -> b.reorder(shown, from.index, to.index) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.id }) { t ->
            ReorderableItem(reorder, key = t.id) { _ ->
                run {
                    val raw = if (t.incognito) 0 else b.icons.color(Urls.host(t.url))
                    val color = if (raw == 0) (if (t.incognito) p.toolbarIncognito else p.card) else Color(raw)
                    val light = color.luminance() > 0.55f
                    val text = if (light) Color(0xDE000000) else Color.White
                    val sub = if (light) Color(0x8A000000) else Color(0xB3FFFFFF)
                    val card = remember { arrayOfNulls<LayoutCoordinates>(1) }
                    Box(Modifier.fillMaxWidth().height(124.dp)
                        .longPressDraggableHandle(onDragStarted = { drop.dragging = t }, onDragStopped = { drop.finish(b) })
                        .onGloballyPositioned { card[0] = it }
                        .background(color).clickable { b.pickedCard = card[0]; b.select(t) }) {
                        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 40.dp, top = 12.dp, bottom = 12.dp)) {
                            Thumb(b, t, Modifier.weight(0.42f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White))
                            Column(Modifier.weight(0.58f).fillMaxHeight().padding(start = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                                Text(t.label, color = text, fontSize = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(android.net.Uri.parse(t.url).host ?: "", color = sub, fontSize = 14.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        Box(Modifier.align(Alignment.TopEnd).size(40.dp).clickable(onClickLabel = T.tabClose) { b.closeTab(t) }
                            .semantics { contentDescription = T.tabClose }, contentAlignment = Alignment.Center) {
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
