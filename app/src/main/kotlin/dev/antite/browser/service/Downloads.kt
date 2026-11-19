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
import dev.antite.browser.DialogButton
import dev.antite.browser.UiDialog
import dev.antite.browser.Urls
import dev.antite.browser.data.Entry
import dev.antite.browser.data.Tab
import dev.antite.browser.l10n.T
import dev.antite.browser.lang.catching
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

    // A file from incognito stays in the list, as in Chrome, but without the site it came from.
    fun start(r: DownloadRequest, t: Tab) {
        val name = URLUtil.guessFileName(r.url, r.contentDisposition, r.contentType)
        val mime = r.contentType?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream"
        val source = if (t.incognito) "" else Urls.host(r.url)
        whereTo(t, name) { vault ->
            when (vault) {
                null -> catching { r.body.close() }
                true -> toVault(r.body, name, mime, r.contentLength, source)
                false -> toDownloads(r.body, name, mime, r.contentLength, source)
            }
        }
    }

    fun savePdf(t: Tab) {
        val base = (t.title.ifEmpty { Urls.host(t.url) }).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        b.ensureSession(t).saveAsPdf { input ->
            if (input == null) return@saveAsPdf b.toast(T.downloadPdfFailed)
            val source = if (t.incognito) "" else Urls.host(t.url)
            whereTo(t, "$base.pdf") { vault ->
                when (vault) {
                    null -> catching { input.close() }
                    true -> toVault(input, "$base.pdf", "application/pdf", -1, source)
                    false -> save(input, "$base.pdf", "application/pdf", null, -1, source)
                }
            }
        }
    }

    // A protected category (or incognito, if asked) always sends files to the secure storage, without asking.
    fun protects(t: Tab): Boolean =
        if (t.incognito) b.prefs.bool("incognitoProtectDownloads", false)
        else b.categories.byId(t.category)?.protectDownloads == true

    // true: the secure storage, false: downloads, null: the user dismissed the question.
    private fun whereTo(t: Tab, name: String, done: (Boolean?) -> Unit) {
        if (protects(t)) return done(true)
        when (b.prefs.int("dlTarget", 0)) {
            TARGET_VAULT -> done(true)
            TARGET_ASK -> {
                // The dialog reports a dismissal after an item too, so only the first answer counts.
                var answered = false
                fun answer(v: Boolean?) {
                    if (answered) return
                    answered = true
                    done(v)
                }
                b.show(UiDialog.Items(T.dlTargetQuestion(name = name), listOf(
                    DialogButton(T.dlToDownloads) { answer(false) },
                    DialogButton(T.dlToVault) { answer(true) },
                ), onDismiss = { answer(null) }))
            }
            else -> done(false)
        }
    }

    private fun toDownloads(body: InputStream, name: String, mime: String, total: Long, source: String) {
        if (!b.prefs.bool("dlAsk", false)) return save(body, name, mime, null, total, source)
        b.activity.createDocument(name, mime) { uri ->
            if (uri == null) catching { body.close() } else save(body, name, mime, uri, total, source)
        }
    }

    // Straight into the secure storage: no row in downloads, and the notification does not name the file.
    private fun toVault(body: InputStream, name: String, mime: String, total: Long, source: String) {
        val vault = b.mediaVault
        val item = vault.reserve(name, mime, source)
        var done = 0L
        val task = DownloadService.job(T.dlVaultJob) { t ->
            body.use { input ->
                vault.output(item).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        t.report(done, total)
                    }
                }
            }
        }
        task.total = total
        task.onDone = { status, _ ->
            if (status == DownloadService.DONE) {
                vault.receive(item, done)
                b.toast(T.dlVaultDone)
            } else {
                vault.discard(item)
                if (status == DownloadService.FAILED) b.toast(T.downloadFailed)
            }
        }
        b.toast(T.dlVaultStarted)
        askNotifications()
        DownloadService.enqueue(b.activity, task)
    }

    private fun askNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        if (b.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            b.activity.requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) {}
        }
    }

    private fun save(body: InputStream, name: String, mime: String, chosen: Uri?, total: Long, source: String) {
        // Without it "Open" stops working for a file saved where the user chose once the device restarts.
        if (chosen != null) catching {
            b.activity.contentResolver.takePersistableUriPermission(chosen,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
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

        const val TARGET_VAULT = 1
        const val TARGET_ASK = 2

        fun media(e: Entry) = mime(e).let { it.startsWith("image/") || it.startsWith("video/") }
    }
}
