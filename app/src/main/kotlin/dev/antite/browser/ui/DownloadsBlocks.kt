package dev.antite.browser.ui

import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.Browser
import dev.antite.browser.R
import dev.antite.browser.data.Entry
import dev.antite.browser.data.Options
import dev.antite.browser.l10n.L10n
import dev.antite.browser.l10n.T
import dev.antite.browser.service.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

private val thumbs = LruCache<String, ImageBitmap>(64)

// A thumbnail of a downloaded image or video, as the preview setting says: shown, none, or blurred until tapped.
@Composable
fun MediaThumb(b: Browser, e: Entry, modifier: Modifier, sizePx: Int, fallback: @Composable () -> Unit) {
    val mode = b.prefs.index("dlPreview", Options.downloadPreviews, 0)
    val uri = e.url
    if (mode == 1 || uri == null || !Downloads.media(e)) return fallback()
    val ctx = LocalContext.current
    val key = "$uri@$sizePx"
    val thumb by produceState(thumbs.get(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(sizePx, sizePx), null).asImageBitmap() }
                .getOrNull()?.also { thumbs.put(key, it) }
        }
    }
    val bmp = thumb ?: return fallback()
    var revealed by remember(uri) { mutableStateOf(false) }
    val hidden = mode == 2 && !revealed
    // Blur needs Android 12; before it a hidden thumbnail is not drawn at all.
    if (hidden && Build.VERSION.SDK_INT < 31) {
        Box(modifier.background(LocalPalette.current.pill).clickable { revealed = true }, contentAlignment = Alignment.Center) {
            Ico(R.drawable.ic_download_done)
        }
        return
    }
    Image(bmp, null, contentScale = ContentScale.Crop,
        modifier = modifier.then(if (hidden) Modifier.blur(20.dp) else Modifier)
            .then(if (mode == 2) Modifier.clickable { revealed = !revealed } else Modifier))
}

private fun day(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

// Chrome's download home: days with a date header, images and videos as a two column grid (the newest one as a large
// card), other files as rows.
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.downloadBlocks(b: Browser, items: List<Entry>, open: (Entry) -> Unit, actions: (Entry) -> Unit) {
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag(L10n.code))
    val days = items.groupBy { day(it.time) }.toList().sortedByDescending { it.first }
    var featured = true
    for ((d, list) in days) {
        item(key = "day:$d") {
            Text(format.format(d), color = LocalPalette.current.text, fontSize = 16.sp,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp))
        }
        val media = list.filter { Downloads.done(it) && Downloads.media(it) }
        val files = list - media.toSet()
        var grid = media
        if (featured && media.isNotEmpty()) {
            val top = media.first()
            item(key = "card:${top.id}") { Card(b, top, open, actions) }
            grid = media.drop(1)
        }
        featured = false
        grid.chunked(2).forEach { row ->
            item(key = "row:${row.first().id}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { e ->
                        Box(Modifier.weight(1f).aspectRatio(1f).combinedClickable(onClick = { open(e) }, onLongClick = { actions(e) })) {
                            MediaThumb(b, e, Modifier.fillMaxSize(), 512) { Placeholder() }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        files.forEach { e -> item(key = "file:${e.id}") { FileRow(b, e, open, actions) } }
    }
}

@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxSize().background(LocalPalette.current.pill), contentAlignment = Alignment.Center) {
        Ico(R.drawable.ic_download_done)
    }
}

private fun details(b: Browser, e: Entry): String {
    val size = Downloads.size(e).takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(b.activity, it) }
    val status = if (Downloads.done(e)) null else Downloads.status(e)
    val parts = listOfNotNull(Downloads.source(e).ifEmpty { null }, size, status)
    return if (parts.isEmpty()) "" else parts.joinToString(" ") + " •"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Card(b: Browser, e: Entry, open: (Entry) -> Unit, actions: (Entry) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card)
        .combinedClickable(onClick = { open(e) }, onLongClick = { actions(e) })) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.8f)) { MediaThumb(b, e, Modifier.fillMaxSize(), 1024) { Placeholder() } }
        Row(Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title ?: "", color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(details(b, e), color = p.textSecondary, fontSize = 14.sp, maxLines = 1)
            }
            IconBtn(R.drawable.ic_more_vert, T.actionMore) { actions(e) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(b: Browser, e: Entry, open: (Entry) -> Unit, actions: (Entry) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { open(e) }, onLongClick = { actions(e) })
        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(p.pill), contentAlignment = Alignment.Center) {
            MediaThumb(b, e, Modifier.fillMaxSize(), 80) { Ico(if (Downloads.done(e)) R.drawable.ic_description else R.drawable.ic_download) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title ?: "", color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(details(b, e), color = p.textSecondary, fontSize = 14.sp, maxLines = 1)
        }
        IconBtn(R.drawable.ic_more_vert, T.actionMore) { actions(e) }
    }
}
