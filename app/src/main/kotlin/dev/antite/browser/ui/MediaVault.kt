package dev.antite.browser.ui

import android.app.KeyguardManager
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.format.Formatter
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.R
import dev.antite.browser.UiDialog
import dev.antite.browser.FormField
import dev.antite.browser.data.MediaVault
import dev.antite.browser.data.VaultFile
import dev.antite.browser.l10n.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val vaultThumbs = LruCache<Long, ImageBitmap>(48)

@Composable
fun VaultScreen(b: Browser, shown: Boolean) {
    val p = LocalPalette.current
    var filter by remember { mutableStateOf("") }
    var viewer by remember { mutableStateOf<MediaVault.Item?>(null) }
    // The open folder; null is "All".
    var folder by remember { mutableStateOf<Long?>(null) }
    // Closed with the page: it comes back to the search and the grid, not to the file that was open.
    LaunchedEffect(shown) {
        if (shown) return@LaunchedEffect
        delay(Motion.closeMs().toLong())
        filter = ""
        viewer = null
        folder = null
    }
    val all = b.mediaVault.items
    val folders = b.mediaVault.folders
    if (folder != null && folders.none { it.id == folder }) folder = null
    val items = remember(all, folder) { all.filter { folder == null || it.folder == folder }.sortedByDescending { it.time } }
    val found = remember(items, filter) { if (filter.isEmpty()) items else items.filter { it.name.contains(filter, true) } }
    // Only pictures and videos open inside; other files can be restored to downloads or deleted.
    val viewable = remember(found) { found.filter(::viewable) }
    // The viewer is drawn over the grid, so closing a file returns to the same scroll position and search.
    Box(Modifier.fillMaxSize()) {
        val focus = LocalFocusManager.current
        // The search keeps no focus under the viewer, or the keyboard comes back over it when the app is reopened.
        VaultGrid(b, filter, { filter = it }, items, found, folder, { folder = it }) {
            focus.clearFocus()
            viewer = it
        }
        val viewing = viewer?.takeIf { b.vaultOpen }
        if (viewing != null) {
            BackHandler(enabled = shown) { viewer = null }
            VaultViewer(b, viewing, { viewer = null }, viewable)
        }
    }
}

@Composable
private fun VaultGrid(
    b: Browser, filter: String, onFilter: (String) -> Unit, items: List<MediaVault.Item>, found: List<MediaVault.Item>,
    folder: Long?, onFolder: (Long?) -> Unit, open: (MediaVault.Item) -> Unit,
) {
    val p = LocalPalette.current
    PageScaffold(T.vaultTitle, { b.overlay = dev.antite.browser.Overlay.NONE }, actions = {
        if (b.vaultOpen) IconBtn(R.drawable.ic_add, T.vaultAddFiles) { b.vaultImport(folder) }
    }) {
        if (b.activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) note(T.vaultNoLock)
        if (!b.vaultOpen) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 64.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Ico(R.drawable.ic_shield_lock, size = 44, modifier = Modifier.padding(bottom = 16.dp))
                    Text(T.vaultLocked, color = p.text, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
                    Text(T.vaultLockedText, color = p.textSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 16.dp))
                    TextButton(onClick = { b.unlockVault() }) { Text(T.vaultUnlock, color = p.accent) }
                }
            }
            return@PageScaffold
        }
        item { SearchField(filter, onFilter) }
        item(key = "folders") { FolderStrip(b, folder, onFolder) }
        if (b.mediaVault.pending.isNotEmpty()) note(T.vaultEncrypting(count = b.mediaVault.pending.size))
        if (items.isEmpty()) {
            note(if (folder == null) T.vaultEmpty else T.vaultFolderEmpty)
            if (folder == null) note(T.vaultEmptyHint)
        } else if (found.isEmpty()) {
            note(T.vaultNotFound)
        }
        found.chunked(2).forEach { row ->
            item(key = "row:${row.first().id}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { i ->
                        VaultTile(b, i, Modifier.weight(1f), {
                            if (i.id in b.mediaVault.pending) b.toast(T.vaultEncrypting(count = b.mediaVault.pending.size))
                            else if (viewable(i)) open(i) else b.vaultActions(i)
                        }, { b.vaultActions(i) })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (found.isNotEmpty()) {
            val total = Formatter.formatShortFileSize(b.activity, found.sumOf { it.size })
            note(T.vaultInfo(count = found.size, size = total))
        }
    }
}

// Folders as a strip of chips, like tab categories: "All", the folders, and a chip that makes a new one. A long press
// on a folder renames or deletes it.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderStrip(b: Browser, folder: Long?, onFolder: (Long?) -> Unit) {
    val p = LocalPalette.current
    @Composable
    fun chip(label: String, selected: Boolean, icon: Int? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        Row(
            Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (selected) p.accent else Color.Transparent)
                .then(if (selected) Modifier else Modifier.border(1.dp, p.divider, RoundedCornerShape(17.dp)))
                .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Ico(icon, if (selected) p.onAccent else p.text, 18, Modifier.padding(end = 6.dp))
            Text(label, color = if (selected) p.onAccent else p.text, fontSize = 14.sp, maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "all") { chip(T.tabsAll, folder == null) { onFolder(null) } }
        items(b.mediaVault.folders, key = { it.id }) { f ->
            chip(f.name, folder == f.id, R.drawable.ic_folder, onLong = { b.vaultFolderActions(f) }) { onFolder(f.id) }
        }
        item(key = "new") {
            chip(T.vaultFolderNew, false, R.drawable.ic_add) { b.vaultFolderName(T.vaultFolderNew, "") { b.mediaVault.addFolder(it) { f -> onFolder(f.id) } } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultTile(b: Browser, i: MediaVault.Item, modifier: Modifier, open: () -> Unit, actions: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.padding(vertical = 3.dp)
            .combinedClickable(onClick = open, onLongClick = actions),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(p.pill),
            contentAlignment = Alignment.Center,
        ) {
            VaultThumb(b, i, Modifier.fillMaxSize())
        }
        Text(i.name, color = p.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp))
    }
}

@Composable
private fun VaultThumb(b: Browser, i: MediaVault.Item, modifier: Modifier) {
    // Asked again once a file from before the encryption has been encrypted.
    val thumb by produceState(vaultThumbs.get(i.id), i.id, i.id in b.mediaVault.pending) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { decode(b.mediaVault, i) }.getOrNull()?.also { vaultThumbs.put(i.id, it) }
        }
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

// The full-screen viewer: pages swipe left and right (HorizontalPager), images zoom with telephoto, videos play in
// the media3 PlayerView - nothing here opens in an external app.
@OptIn(UnstableApi::class)
@Composable
private fun VaultViewer(b: Browser, start: MediaVault.Item, onClose: () -> Unit, list: List<MediaVault.Item>) {
    val p = LocalPalette.current
    // The asynchronous codec queueing hangs on Waydroid, the synchronous one plays; decoder fallback finds a
    // working codec when the preferred one fails.
    val player = remember {
        ExoPlayer.Builder(b.activity, DefaultRenderersFactory(b.activity)
            .forceDisableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true))
            // Played straight from the encrypted file: nothing decrypted is written to the disk.
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { VaultDataSource(b.mediaVault) }))
            .build()
            .apply { playWhenReady = true }
    }
    // The video stops when the app is left: the storage may stay unlocked ("after 5 minutes", "never").
    DisposableEffect(Unit) {
        val stop = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        b.activity.lifecycle.addObserver(stop)
        onDispose {
            b.activity.lifecycle.removeObserver(stop)
            player.release()
        }
    }
    val pager = rememberPagerState(initialPage = list.indexOfFirst { it.id == start.id }.coerceAtLeast(0)) { list.size }
    val current = list.getOrNull(pager.currentPage)
    // One player for the whole viewer: the file of the current page is loaded into it, a swipe to another video
    // switches the file, a swipe to a picture stops it.
    LaunchedEffect(current) {
        val i = current
        if (i != null && i.mime.startsWith("video/")) {
            player.setMediaItem(MediaItem.Builder().setUri(VaultDataSource.uri(i)).setMimeType(i.mime).build())
            player.prepare()
        } else player.stop()
    }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(current) {
        if (current?.mime?.startsWith("video/") != true) return@LaunchedEffect
        while (true) {
            playing = player.isPlaying
            posMs = player.currentPosition.coerceAtLeast(0)
            durMs = player.duration.takeIf { it > 0 } ?: 0
            delay(150)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { list[it].id }, modifier = Modifier.fillMaxSize()) { page ->
            val i = list[page]
            if (i.mime.startsWith("video/")) {
                VideoPage(player, page == pager.currentPage)
            } else {
                ZoomableImage(b.mediaVault, i)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(R.drawable.ic_arrow_back, T.actionBack, tint = Color.White, onClick = onClose)
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: "", color = Color.White, fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis)
                Text("${pager.currentPage + 1} / ${list.size}", color = Color(0xB3FFFFFF), fontSize = 13.sp)
            }
        }
        val i = current
        if (i != null && i.mime.startsWith("video/")) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) T.vaultPause else T.vaultPlay, tint = Color.White) {
                    if (playing) player.pause() else player.play()
                }
                var drag by remember { mutableFloatStateOf(-1f) }
                Slider(
                    value = if (drag >= 0) drag else if (durMs > 0) posMs.toFloat() / durMs else 0f,
                    onValueChange = { drag = it.coerceIn(0f, 1f) },
                    onValueChangeFinished = {
                        if (drag >= 0 && durMs > 0) player.seekTo((drag * durMs).toLong())
                        drag = -1f
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = Color(0x40FFFFFF)),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(player: ExoPlayer, current: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // Own Compose controls instead of the library ones; without the controller the view does not
                    // consume drags, so the pager still swipes over the video.
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            onRelease = { it.player = null },
            update = { v -> v.setPlayer(player.takeIf { current }) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ZoomableImage(vault: MediaVault, item: MediaVault.Item) {
    val bmp by produceState<ImageBitmap?>(null, item.id) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { decode(vault, item, 2048) }.getOrNull() }
    }
    val b = bmp
    if (b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Ico(R.drawable.ic_image, tint = Color(0x80FFFFFF), size = 40)
        }
        return
    }
    val zoom = rememberZoomableState()
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Image(b, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().zoomable(zoom))
    }
}

private fun viewable(i: MediaVault.Item) = i.mime.startsWith("image/") || i.mime.startsWith("video/")

private fun decode(vault: MediaVault, item: MediaVault.Item, target: Int = 512): ImageBitmap? = when {
    item.mime.startsWith("video/") -> MediaMetadataRetriever().use { r ->
        val reader = vault.reader(item)
        r.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = reader.read(position, buffer, offset, size)
            override fun getSize() = reader.length
            override fun close() = reader.close()
        })
        r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.asImageBitmap()
    }
    !item.mime.startsWith("image/") -> null
    else -> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        vault.reader(item).stream().use { BitmapFactory.decodeStream(it, null, opts) }
        val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / target)
        vault.reader(item).stream().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.asImageBitmap()
    }
}

// media3 reads a video of the storage through this: any position, decrypted chunk by chunk in memory.
@UnstableApi
private class VaultDataSource(private val vault: MediaVault) : BaseDataSource(false) {
    companion object {
        fun uri(item: MediaVault.Item): Uri = Uri.parse("vault://item/${item.id}")
    }

    private var reader: VaultFile.Reader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var left = 0L
    private var opened = false

    override fun open(spec: DataSpec): Long {
        uri = spec.uri
        transferInitializing(spec)
        val id = spec.uri.lastPathSegment?.toLongOrNull()
        val item = vault.items.firstOrNull { it.id == id } ?: throw java.io.FileNotFoundException(spec.uri.toString())
        val r = vault.reader(item)
        reader = r
        if (spec.position > r.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = spec.position
        left = if (spec.length != C.LENGTH_UNSET.toLong()) minOf(spec.length, r.length - position) else r.length - position
        opened = true
        transferStarted(spec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(position, buffer, offset, minOf(length.toLong(), left).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri() = uri

    override fun close() {
        reader?.close()
        reader = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

// Tap a tile: the file is not opened in other apps from here, it either goes back to downloads or is deleted.
fun Browser.vaultActions(i: MediaVault.Item) {
    show(UiDialog.Items(i.name, listOfNotNull(
        DialogButton(T.vaultToFolder) {
            show(UiDialog.Items(T.vaultToFolder, listOf(DialogButton(T.vaultNoFolder) { mediaVault.move(i, null) }) +
                mediaVault.folders.map { f -> DialogButton(f.name) { mediaVault.move(i, f.id) } }))
        }.takeIf { mediaVault.folders.isNotEmpty() },
        DialogButton(T.vaultRestore) {
            mediaVault.restore(i) { ok ->
                downloads.revision.intValue++
                toast(if (ok) T.vaultRestored else T.vaultRestoreFailed)
            }
        },
        DialogButton(T.actionDelete) {
            confirm(T.vaultDeleteConfirm, T.actionDelete) {
                mediaVault.delete(i) { toast(T.vaultDeleted) }
            }
        },
    )))
}

fun Browser.vaultFolderName(title: String, name: String, done: (String) -> Unit) {
    show(UiDialog.Form(title, null, listOf(FormField(T.vaultFolderName, name, FormField.Kind.NAME)), onConfirm = { v ->
        val entered = v[0].trim()
        if (entered.isEmpty()) return@Form false
        done(entered)
        true
    }))
}

fun Browser.vaultFolderActions(f: MediaVault.Folder) {
    show(UiDialog.Items(f.name, listOf(
        DialogButton(T.vaultFolderRename) { vaultFolderName(T.vaultFolderRename, f.name) { mediaVault.renameFolder(f, it) } },
        DialogButton(T.vaultFolderDelete) { confirm(T.vaultFolderDeleteConfirm(name = f.name), T.actionDelete) { mediaVault.deleteFolder(f) } },
    )))
}

// Files from the system file chooser go into the open folder.
fun Browser.vaultImport(folder: Long?) = activity.openDocuments { uris ->
    if (uris.isEmpty()) return@openDocuments
    toast(T.vaultAdding(count = uris.size))
    mediaVault.import(uris, folder, prefs.bool("vaultDeleteOriginal", true)) { added, failed -> vaultImported(added, failed) }
}

fun Browser.vaultImported(added: Int, failed: Int) =
    toast(if (failed == 0) T.vaultAdded(count = added) else T.vaultAddedSome(count = added, failed = failed))
