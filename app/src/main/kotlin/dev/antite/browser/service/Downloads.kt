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
        if (!askTarget) return save(r.body, name, mime, null, r.contentLength)
        b.activity.createDocument(name, mime) { uri ->
            if (uri == null) runCatching { r.body.close() } else save(r.body, name, mime, uri, r.contentLength)
        }
    }

    fun savePdf(t: Tab) {
        val base = (t.title.ifEmpty { Urls.host(t.url) }).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        b.ensureSession(t).saveAsPdf { input ->
            if (input != null) save(input, "$base.pdf", "application/pdf", null, -1) else b.toast("Не удалось сохранить PDF")
        }
    }

    private fun askNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        if (b.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            b.activity.requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) {}
        }
    }

    private fun save(body: InputStream, name: String, mime: String, chosen: Uri?, total: Long) {
        b.toast("Загрузка: $name")
        askNotifications()
        b.activity.lifecycleScope.launch {
            val id = b.db.io { addDownload(name, mime) }
            DownloadService.enqueue(b.activity, DownloadService.Task(id, name, mime, total, chosen, body))
            revision.intValue++
        }
    }

    fun cancel(e: Entry) = DownloadService.cancel(e.id)

    fun open(e: Entry) {
        val url = e.url ?: return b.toast("Файл ещё не готов")
        try {
            b.activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime(e))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (ex: Exception) {
            b.toast("Нет приложения, чтобы открыть этот файл")
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
            DownloadService.DONE -> "Скачано"
            DownloadService.FAILED -> "Ошибка"
            DownloadService.CANCELLED -> "Отменено"
            else -> when (val p = DownloadService.progress[e.id]) {
                null, -1f -> "Загружается"
                else -> "Загружается ${(p * 100).toInt()}%"
            }
        }

        fun loading(e: Entry) = DownloadService.isActive(e.id)

        private fun mime(e: Entry): String = e.extra?.split('|')?.getOrNull(1)?.ifEmpty { null } ?: "*/*"
    }
}
