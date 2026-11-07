package dev.antite.browser.service

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.URLUtil
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.Browser
import dev.antite.browser.Urls
import dev.antite.browser.data.Entry
import dev.antite.browser.data.Tab
import dev.antite.engine.DownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class Downloads(private val b: Browser) {
    val revision = mutableIntStateOf(0)

    fun start(r: DownloadRequest, askTarget: Boolean) {
        val name = URLUtil.guessFileName(r.url, r.contentDisposition, r.contentType)
        val mime = r.contentType?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream"
        if (!askTarget) return save(r.body, name, mime, null)
        b.activity.createDocument(name, mime) { uri ->
            if (uri == null) runCatching { r.body.close() } else save(r.body, name, mime, uri)
        }
    }

    fun savePdf(t: Tab) {
        val base = (t.title.ifEmpty { Urls.host(t.url) }).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        b.ensureSession(t).saveAsPdf { input ->
            if (input != null) save(input, "$base.pdf", "application/pdf", null) else b.toast("Не удалось сохранить PDF")
        }
    }

    private fun save(body: InputStream, name: String, mime: String, chosen: Uri?) {
        b.toast("Загрузка: $name")
        val cr = b.activity.applicationContext.contentResolver
        b.activity.lifecycleScope.launch {
            val id = b.db.io { addDownload(name, mime) }
            revision.intValue++
            val (ok, target) = withContext(Dispatchers.IO) {
                val target = chosen ?: cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                })
                if (target == null) {
                    runCatching { body.close() }
                    return@withContext false to null
                }
                val ok = try {
                    body.use { input -> cr.openOutputStream(target)!!.use { input.copyTo(it, 64 * 1024) } }
                    true
                } catch (e: Exception) {
                    Log.w("Antite", "download failed: $name", e)
                    false
                }
                if (chosen == null) {
                    if (ok) cr.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    else cr.delete(target, null, null)
                }
                ok to target
            }
            b.db.io { finishDownload(id, if (ok) target.toString() else null, ok) }
            revision.intValue++
            b.toast(if (ok) "Скачано: $name" else "Ошибка загрузки: $name")
        }
    }

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
            "done" -> "Скачано"
            "failed" -> "Ошибка"
            else -> "Загружается"
        }

        private fun mime(e: Entry): String = e.extra?.split('|')?.getOrNull(1)?.ifEmpty { null } ?: "*/*"
    }
}
