package dev.antite.browser.ui

import android.app.KeyguardManager
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.Browser
import dev.antite.browser.R
import dev.antite.browser.data.MediaVault
import dev.antite.browser.data.VaultFileId
import dev.antite.browser.data.VaultFolderId
import dev.antite.browser.l10n.T
import dev.antite.browser.ring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val vaultThumbs = LruCache<VaultFileId, ImageBitmap>(48)

private const val SORT = "vaultSort"
private val TOP = VaultFolderId(-1L)

private fun sorted(items: List<MediaVault.Item>, sort: String) = when (sort) {
    "name" -> items.sortedBy { it.name.lowercase() }
    "size" -> items.sortedByDescending { it.size }
    else -> items.sortedByDescending { it.time }
}

// Selected files dragged onto a folder tile or a step of the path move there. Targets and the finger are in window
// coordinates; TOP is the top of the storage.
private class FolderDrop {
    val targets = mutableStateMapOf<VaultFolderId, Rect>()
    var dragging by mutableStateOf(false)
    var finger by mutableStateOf(Offset.Unspecified)

    val target: VaultFolderId? get() = if (!dragging || !finger.isSpecified) null else targets.entries.firstOrNull { it.value.contains(finger) }?.key

    fun finish(b: Browser, selected: List<MediaVault.Item>, clear: () -> Unit) {
        val to = target
        dragging = false
        finger = Offset.Unspecified
        if (to == null || selected.isEmpty()) return
        val folder = to.takeIf { it != TOP }
        val moving = selected.filter { it.folder != folder }
        if (moving.isEmpty()) return
        b.mediaVault.move(moving, folder)
        b.toast(T.vaultMoved(count = moving.size))
        clear()
    }
}

@Composable
fun VaultScreen(b: Browser, shown: Boolean) {
    var filter by remember { mutableStateOf("") }
    var viewer by remember { mutableStateOf<MediaVault.Item?>(null) }
    // The open folder; null is the top of the storage.
    var folder by remember { mutableStateOf<VaultFolderId?>(null) }
    var selected by remember { mutableStateOf(emptySet<VaultFileId>()) }
    var sort by remember { mutableStateOf(b.prefs.string(SORT, "date")) }
    // Closed with the page: it comes back to the search and the grid, not to the file that was open.
    LaunchedEffect(shown) {
        if (shown) return@LaunchedEffect
        delay(Motion.closeMs().toLong())
        filter = ""
        viewer = null
        folder = null
        selected = emptySet()
    }
    val all = b.mediaVault.items
    val folders = b.mediaVault.folders
    if (folder != null && folders.none { it.id == folder }) folder = null
    // Files that went away (moved by another way, deleted) leave the selection.
    val present = remember(all) { all.map { it.id }.toSet() }
    if (selected.any { it !in present }) selected = selected.filter { it in present }.toSet()
    val items = remember(all, folder, sort) { sorted(all.filter { it.folder == folder }, sort) }
    // The search looks through every folder.
    val found = remember(items, all, filter, sort) { if (filter.isEmpty()) items else sorted(all.filter { it.name.contains(filter, true) }, sort) }
    BackHandler(enabled = shown && viewer == null && selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = shown && viewer == null && selected.isEmpty() && folder != null) {
        folder = folders.firstOrNull { it.id == folder }?.parent
    }
    // Only pictures and videos open inside; other files can be restored to downloads or deleted.
    val viewable = remember(found) { found.filter(::viewable) }
    val drop = remember { FolderDrop() }
    val root = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val chosen = all.filter { it.id in selected }
    Box(Modifier.fillMaxSize().onGloballyPositioned { root[0] = it }) {
        val focus = LocalFocusManager.current
        // The search keeps no focus under the viewer, or the keyboard comes back over it when the app is reopened.
        VaultGrid(b, VaultGridState(filter, items, found, folder, selected, sort, drop),
            onFilter = { filter = it },
            onFolder = { folder = it },
            onSort = { s ->
                sort = s
                b.prefs.edit { putString(SORT, s) }
            },
            onSelect = { selected = it },
            open = { focus.clearFocus(); viewer = it })
        // What is being dragged follows the finger: a count over the target.
        val at = drop.finger
        val local = root[0]?.takeIf { it.isAttached && at.isSpecified }?.windowToLocal(at)
        if (drop.dragging && local != null) {
            Text("${chosen.size}", color = LocalPalette.current.background, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset { IntOffset(local.x.roundToInt() - 20, local.y.roundToInt() - 60) }
                    .clip(RoundedCornerShape(16.dp)).background(LocalPalette.current.accent).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        val viewing = viewer?.takeIf { b.vaultAccess.open }
        if (viewing != null) {
            BackHandler(enabled = shown) { viewer = null }
            VaultViewer(b, viewing, { viewer = null }, viewable)
        }
    }
}

private class VaultGridState(
    val filter: String, val items: List<MediaVault.Item>, val found: List<MediaVault.Item>, val folder: VaultFolderId?,
    val selected: Set<VaultFileId>, val sort: String, val drop: FolderDrop,
)

@Composable
private fun VaultGrid(
    b: Browser, s: VaultGridState, onFilter: (String) -> Unit, onFolder: (VaultFolderId?) -> Unit, onSort: (String) -> Unit,
    onSelect: (Set<VaultFileId>) -> Unit, open: (MediaVault.Item) -> Unit,
) {
    val p = LocalPalette.current
    val selecting = s.selected.isNotEmpty()
    val chosen = { b.mediaVault.items.filter { it.id in s.selected } }
    PageScaffold(
        if (selecting) T.vaultSelected(count = s.selected.size) else T.vaultTitle,
        { if (selecting) onSelect(emptySet()) else b.overlay = dev.antite.browser.Overlay.NONE },
        actions = {
            if (!b.vaultAccess.open) return@PageScaffold
            if (selecting) {
                IconBtn(R.drawable.ic_select_all, T.vaultSelectAll) { onSelect(s.found.map { it.id }.toSet()) }
                IconBtn(R.drawable.ic_folder, T.vaultToFolder) { b.vaultMove(chosen()) { onSelect(emptySet()) } }
                IconBtn(R.drawable.ic_delete, T.actionDelete) { b.vaultDelete(chosen()) { onSelect(emptySet()) } }
                IconBtn(R.drawable.ic_more_vert, T.vaultMore) { b.vaultFileMenu(chosen()) { onSelect(emptySet()) } }
            } else {
                IconBtn(R.drawable.ic_sort, T.vaultSort) { b.vaultSortMenu(s.sort, onSort) }
                IconBtn(R.drawable.ic_add, T.vaultAdd) { b.vaultAddMenu(s.folder, onFolder) }
            }
        },
    ) {
        if (!b.ring.mode.usesPassword && b.activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            item { Badge(T.vaultNoLockBadge, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }
        if (!b.vaultAccess.open) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 64.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Ico(R.drawable.ic_shield_lock, size = 44, modifier = Modifier.padding(bottom = 16.dp))
                    Text(T.vaultLocked, color = p.text, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
                    TextButton(onClick = { b.vaultAccess.unlock() }) { Text(T.vaultUnlock, color = p.accent) }
                }
            }
            return@PageScaffold
        }
        item { SearchField(s.filter, onFilter) }
        item(key = "path") { PathLine(b, s.folder, s.drop, onFolder) }
        if (b.mediaVault.pending.isNotEmpty()) item { Badge(T.vaultEncrypting, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        val subfolders = if (s.filter.isEmpty()) b.mediaVault.folders.filter { it.parent == s.folder }.sortedBy { it.name.lowercase() } else emptyList()
        if (s.items.isEmpty() && subfolders.isEmpty()) {
            note(if (s.folder == null) T.vaultEmpty else T.vaultFolderEmpty)
            if (s.folder == null) item(key = "ways") { AddWays() }
        } else if (s.filter.isNotEmpty() && s.found.isEmpty()) {
            note(T.vaultNotFound)
        }
        subfolders.chunked(2).forEach { row ->
            item(key = "folders:${row.first().id}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { f -> FolderTile(b, f, s.drop, Modifier.weight(1f)) { onFolder(f.id) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        s.found.chunked(2).forEach { row ->
            item(key = "row:${row.first().id}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { i ->
                        val on = i.id in s.selected
                        VaultTile(b, i, on, s.drop, Modifier.weight(1f),
                            tap = {
                                when {
                                    selecting -> onSelect(if (on) s.selected - i.id else s.selected + i.id)
                                    i.id in b.mediaVault.pending -> b.toast(T.vaultEncrypting)
                                    viewable(i) -> open(i)
                                    else -> onSelect(setOf(i.id))
                                }
                            },
                            hold = { onSelect(s.selected + i.id) },
                            dropped = { s.drop.finish(b, chosen() + listOf(i).filter { it.id !in s.selected }) { onSelect(emptySet()) } })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// How files get in, as plates instead of a paragraph.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddWays() {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(T.vaultWayAdd, T.vaultWayShare, T.vaultWayDownloads, T.vaultWayDownloadHere).forEach { Badge(it) }
    }
}

private fun Modifier.dropTarget(drop: FolderDrop, id: VaultFolderId): Modifier = onGloballyPositioned { drop.targets[id] = it.boundsInWindow() }

// Where the grid is: the top of the storage and the folders down to the open one, each a step back and a place to
// drop files on. New folders come from "+" at the top, which stays in reach however deep the path gets.
@Composable
private fun PathLine(b: Browser, folder: VaultFolderId?, drop: FolderDrop, onFolder: (VaultFolderId?) -> Unit) {
    val p = LocalPalette.current
    val path = b.mediaVault.path(folder)
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        item(key = "top") {
            val aimed = drop.target == TOP
            Text(T.vaultTitle, color = if (folder == null) p.text else p.accent, fontSize = 14.sp, maxLines = 1,
                modifier = Modifier.dropTarget(drop, TOP).clip(RoundedCornerShape(8.dp))
                    .background(if (aimed) p.pill else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onFolder(null) }.padding(6.dp))
        }
        items(path, key = { it.id.value }) { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("›", color = p.textSecondary, fontSize = 14.sp)
                val aimed = drop.target == f.id
                Text(f.name, color = if (f.id == folder) p.text else p.accent, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.dropTarget(drop, f.id).clip(RoundedCornerShape(8.dp))
                        .background(if (aimed) p.pill else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onFolder(f.id) }.padding(6.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(b: Browser, f: MediaVault.Folder, drop: FolderDrop, modifier: Modifier, open: () -> Unit) {
    val p = LocalPalette.current
    val aimed = drop.target == f.id
    Row(
        modifier.height(56.dp).dropTarget(drop, f.id).clip(RoundedCornerShape(12.dp)).background(p.pill)
            .then(if (aimed) Modifier.border(2.dp, p.accent, RoundedCornerShape(12.dp)) else Modifier)
            .combinedClickable(onClick = open, onLongClick = { b.vaultFolderActions(f) }).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(R.drawable.ic_folder, size = 24, modifier = Modifier.padding(end = 10.dp))
        Text(f.name, color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// A tap opens (or, while selecting, picks); a long press selects, and a finger kept down after it drags the
// selection onto a folder.
@Composable
private fun VaultTile(
    b: Browser, i: MediaVault.Item, on: Boolean, drop: FolderDrop, modifier: Modifier, tap: () -> Unit, hold: () -> Unit,
    dropped: () -> Unit,
) {
    val p = LocalPalette.current
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // The gesture outlives recompositions; it must call what the latest one passed, or a tap would see an old selection.
    val onTap by rememberUpdatedState(tap)
    val onHold by rememberUpdatedState(hold)
    val onDropped by rememberUpdatedState(dropped)
    Column(
        modifier.padding(vertical = 3.dp).onGloballyPositioned { coords[0] = it }
            .pointerInput(i.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val long = awaitLongPressOrCancellation(down.id)
                    if (long == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && up.changedToUp() && !up.isConsumed) onTap()
                        return@awaitEachGesture
                    }
                    onHold()
                    drop.dragging = true
                    drag(long.id) { change ->
                        coords[0]?.takeIf { it.isAttached }?.let { drop.finger = it.localToWindow(change.position) }
                        change.consume()
                    }
                    onDropped()
                }
            },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(p.pill)
                .then(if (on) Modifier.border(3.dp, p.accent, RoundedCornerShape(12.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            VaultThumb(b, i, Modifier.fillMaxSize())
            if (on) Ico(R.drawable.ic_check_circle, p.accent, 26, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(i.name, color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp))
    }
}

@Composable
private fun VaultThumb(b: Browser, i: MediaVault.Item, modifier: Modifier) {
    val waiting = i.id in b.mediaVault.pending
    // Held per file: a tile handed another file (the next one slides in when a file moves away) must not keep the
    // old picture, which produceState would. Asked again once a file from before the encryption has been encrypted.
    var thumb by remember(i.id) { mutableStateOf(vaultThumbs.get(i.id)) }
    LaunchedEffect(i.id, waiting) {
        if (thumb == null) thumb = withContext(Dispatchers.IO) { thumbnail(b.mediaVault, i) }?.also { vaultThumbs.put(i.id, it) }
    }
    val bmp = thumb
    if (bmp != null) {
        Image(bmp, null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Ico(when {
                i.mime.startsWith("video/") -> R.drawable.ic_videocam
                i.mime.startsWith("image/") -> R.drawable.ic_image
                else -> R.drawable.ic_description
            }, size = 40)
        }
    }
}
