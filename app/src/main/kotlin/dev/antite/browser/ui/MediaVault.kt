package dev.antite.browser.ui

import android.app.KeyguardManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.antite.browser.Browser
import dev.antite.browser.DialogButton
import dev.antite.browser.R
import dev.antite.browser.UiDialog
import dev.antite.browser.data.MediaVault
import dev.antite.browser.l10n.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val vaultThumbs = LruCache<Long, ImageBitmap>(48)

@Composable
fun VaultScreen(b: Browser, shown: Boolean) {
    val p = LocalPalette.current
    // The lock setting "after 5 minutes" is checked against the moment the page is shown.
    LaunchedEffect(shown) {
        if (shown) b.vaultRelock()
    }
    PageScaffold(T.vaultTitle, { b.overlay = dev.antite.browser.Overlay.NONE }) {
        if (b.activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) note(T.vaultNoLock)
        if (!b.vaultUnlocked) {
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
        val items = b.mediaVault.items.sortedByDescending { it.time }
        if (items.isEmpty()) {
            note(T.vaultEmpty)
            note(T.vaultEmptyHint)
        }
        items.chunked(2).forEach { row ->
            item(key = "row:${row.first().id}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { i -> VaultTile(b, i, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (items.isNotEmpty()) {
            val total = Formatter.formatShortFileSize(b.activity, items.sumOf { it.size })
            note(T.vaultInfo(count = items.size, size = total))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultTile(b: Browser, i: MediaVault.Item, modifier: Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.padding(vertical = 3.dp)
            .combinedClickable(onClick = { b.vaultActions(i) }, onLongClick = { b.vaultActions(i) }),
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
    val thumb by produceState(vaultThumbs.get(i.id), i.id) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { decode(b.mediaVault.file(i), i.mime) }.getOrNull()?.also { vaultThumbs.put(i.id, it) }
        }
    }
    val bmp = thumb
    if (bmp != null) {
        Image(bmp, null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Ico(if (i.mime.startsWith("video/")) R.drawable.ic_videocam else R.drawable.ic_image, size = 40)
        }
    }
}

private fun decode(f: File, mime: String): ImageBitmap? = when {
    mime.startsWith("video/") -> MediaMetadataRetriever().use { r ->
        r.setDataSource(f.absolutePath)
        r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.asImageBitmap()
    }
    else -> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 512)
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

// Tap a tile: the file is not opened in other apps from here, it either goes back to downloads or is deleted.
fun Browser.vaultActions(i: MediaVault.Item) {
    show(UiDialog.Items(i.name, listOf(
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
