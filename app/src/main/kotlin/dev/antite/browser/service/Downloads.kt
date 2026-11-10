package dev.antite.browser.service

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.URLUtil
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.Browser
import dev.antite.browser.Urls
import dev.antite.browser.data.Entry
import dev.antite.browser.data.Tab
import dev.antite.browser.l10n.T
import dev.antite.engine.DownloadRequest
import kotlinx.coroutines.launch
import java.io.InputStream

class Downloads(private val b: Browser) {
    val revision = mutableIntStateOf(0)
    private var askedNotifications = false

    init {
        DownloadService.onFinished = { revision.intValue++ }
        b.activity.lifecycleScope.launch {
            b.db.io { failStaleDownloads(DownloadService.activeIds()) }
            revision.intValue++
        }
    }

    fun start(r: DownloadRequest, askTarget: Boolean) {
        val name = URLUtil.guessFileName(r.url, r.contentDisposition, r.contentType)
        val mime = r.contentType?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream"
        if (!askTarget) return save(r.body, name, mime, null, r.contentLength, Urls.host(r.url))
        b.activity.createDocument(name, mime) { uri ->
            if (uri == null) runCatching { r.body.close() } else save(r.body, name, mime, uri, r.contentLength, Urls.host(r.url))
        }
    }

    fun savePdf(t: Tab) {
        val base = (t.title.ifEmpty { Urls.host(t.url) }).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        b.ensureSession(t).saveAsPdf { input ->
            if (input != null) save(input, "$base.pdf", "application/pdf", null, -1, Urls.host(t.url)) else b.toast(T.downloadPdfFailed)
        }
    }

    private fun askNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        if (b.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            b.activity.requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) {}
        }
    }

    private fun save(body: InputStream, name: String, mime: String, chosen: Uri?, total: Long, source: String) {
        b.toast(T.downloadStarted(name = name))
        askNotifications()
        b.activity.lifecycleScope.launch {
            val id = b.db.io { addDownload(name, mime, source) }
            DownloadService.enqueue(b.activity, DownloadService.Task(id, name, mime, total, chosen, body))
            revision.intValue++
        }
    }

    fun cancel(e: Entry) = DownloadService.cancel(e.id)

    fun open(e: Entry) {
        val url = e.url ?: return b.toast(T.downloadNotReady)
        try {
            b.activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime(e))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (ex: Exception) {
            b.toast(T.downloadNoApp)
        }
    }

    fun share(e: Entry) {
        val url = e.url ?: return
        b.activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
            .setType(mime(e))
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(url))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }

    companion object {
        fun status(e: Entry): String = when (e.extra?.split('|')?.firstOrNull()) {
            DownloadService.DONE -> T.downloadStatusDone
            DownloadService.FAILED -> T.downloadStatusFailed
            DownloadService.CANCELLED -> T.downloadStatusCancelled
            else -> when (val p = DownloadService.progress[e.id]) {
                null, -1f -> T.downloadStatusLoading
                else -> T.downloadStatusPercent(percent = (p * 100).toInt())
            }
        }

        fun loading(e: Entry) = DownloadService.isActive(e.id)

        fun mime(e: Entry): String = e.extra?.split('|')?.getOrNull(1)?.ifEmpty { null } ?: "*/*"

        fun source(e: Entry): String = e.extra?.split('|')?.getOrNull(2) ?: ""

        fun size(e: Entry): Long = e.extra?.split('|')?.getOrNull(3)?.toLongOrNull() ?: -1

        fun done(e: Entry) = e.extra?.split('|')?.firstOrNull() == DownloadService.DONE && e.url != null

        fun media(e: Entry) = mime(e).let { it.startsWith("image/") || it.startsWith("video/") }
    }
}
