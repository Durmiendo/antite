package dev.antite.browser.ui

import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import dev.antite.browser.Browser
import dev.antite.browser.R
import dev.antite.browser.data.MediaVault
import dev.antite.browser.data.VaultReader
import dev.antite.browser.l10n.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The full-screen viewer: pages swipe left and right (HorizontalPager), images zoom with telephoto, videos play in
// the media3 PlayerView - nothing here opens in an external app.
@OptIn(UnstableApi::class)
@Composable
internal fun VaultViewer(b: Browser, start: MediaVault.Item, onClose: () -> Unit, list: List<MediaVault.Item>) {
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
    LaunchedEffect(list.isEmpty()) { if (list.isEmpty()) onClose() }
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
    val scope = rememberCoroutineScope()
    // A tap on the page hides or shows the controls, as in a gallery.
    var controls by remember { mutableStateOf(true) }
    fun go(delta: Int) {
        val target = pager.currentPage + delta
        if (target in 0 until list.size) scope.launch { pager.animateScrollToPage(target) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { list[it].id },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { controls = !controls } }) { page ->
            val i = list[page]
            if (i.mime.startsWith("video/")) {
                VideoPage(player, page == pager.currentPage)
            } else {
                ZoomableImage(b.mediaVault, i) { controls = !controls }
            }
        }
        if (controls && pager.currentPage > 0) Box(Modifier.align(Alignment.CenterStart).padding(4.dp)
            .background(Color(0x66000000), RoundedCornerShape(24.dp))) {
            IconBtn(R.drawable.ic_chevron_left, T.vaultPrevious, tint = Color.White) { go(-1) }
        }
        if (controls && pager.currentPage < list.size - 1) Box(Modifier.align(Alignment.CenterEnd).padding(4.dp)
            .background(Color(0x66000000), RoundedCornerShape(24.dp))) {
            IconBtn(R.drawable.ic_chevron_right, T.vaultNext, tint = Color.White) { go(1) }
        }
        if (controls) Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(R.drawable.ic_arrow_back, T.actionBack, tint = Color.White, onClick = onClose)
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: "", color = Color.White, fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis)
                Text("${pager.currentPage + 1} / ${list.size}", color = Color(0xB3FFFFFF), fontSize = 13.sp)
            }
            // The same actions as the grid's; a file moved away or deleted leaves the pager, which shows the next one.
            current?.let { c -> IconBtn(R.drawable.ic_more_vert, T.vaultMore, tint = Color.White) { b.vaultFileMenu(listOf(c)) } }
        }
        val i = current
        if (controls && i != null && i.mime.startsWith("video/")) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(R.drawable.ic_replay_10, T.vaultBack10, tint = Color.White) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
                IconBtn(if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) T.vaultPause else T.vaultPlay, tint = Color.White) {
                    if (playing) player.pause() else player.play()
                }
                IconBtn(R.drawable.ic_forward_10, T.vaultForward10, tint = Color.White) { player.seekTo(player.currentPosition + 10_000) }
                Text(time(posMs), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
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
                Text(time(durMs), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
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
private fun ZoomableImage(vault: MediaVault, item: MediaVault.Item, onTap: () -> Unit) {
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
        Image(b, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().zoomable(zoom, onClick = { onTap() }))
    }
}

// The stored preview, or one made now from the file and stored for the next time.
internal fun thumbnail(vault: MediaVault, item: MediaVault.Item): ImageBitmap? {
    vault.thumb(item)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it.asImageBitmap() } }
    val made = runCatching { decode(vault, item, 320) }.getOrNull() ?: return null
    val out = java.io.ByteArrayOutputStream()
    made.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
    vault.saveThumb(item, out.toByteArray())
    return made
}

internal fun viewable(i: MediaVault.Item) = i.mime.startsWith("image/") || i.mime.startsWith("video/")

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

    private var reader: VaultReader? = null
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
