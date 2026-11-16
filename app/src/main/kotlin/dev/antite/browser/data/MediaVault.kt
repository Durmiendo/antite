package dev.antite.browser.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.antite.browser.service.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Pictures and videos taken out of the downloads list: the file is copied into the app's private folder and the
// public one is deleted, so other apps and the gallery no longer see it. The index is a small JSON file next to the
// files; names and metadata live there, the file itself is named after its id.
class MediaVault(private val context: Context) {
    companion object {
        private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_").take(100).ifEmpty { "file" }
    }

    data class Item(val id: Long, val name: String, val mime: String, val size: Long, val time: Long, val source: String)

    // One worker: every operation rewrites the whole index, so two at once would lose each other's entries.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val index = File(dir, "index.json")

    var items by mutableStateOf(load())
        private set
    val revision = mutableIntStateOf(0)
    private var nextId = (items.maxOfOrNull { it.id } ?: 0) + 1

    fun file(item: Item) = File(dir, item.id.toString())

    private fun load(): List<Item> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(o.getLong("id"), o.getString("name"), o.getString("mime"), o.getLong("size"),
                o.getLong("time"), o.optString("source"))
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val a = JSONArray()
        items.forEach { i ->
            a.put(JSONObject()
                .put("id", i.id).put("name", i.name).put("mime", i.mime)
                .put("size", i.size).put("time", i.time).put("source", i.source))
        }
        index.writeText(a.toString())
    }

    private fun refresh() {
        main.post { revision.intValue++ }
    }

    // A download entry moves in: the file is copied and indexed, only then the original (if asked) goes away
    // together with its downloads row.
    fun add(e: Entry, deleteOriginal: Boolean, done: (Boolean) -> Unit) {
        val uri = e.url ?: return done(false)
        val item = Item(nextId++, sanitize(e.title ?: "file"), e.extra?.split('|')?.getOrNull(1)?.ifEmpty { null }
            ?: "application/octet-stream", 0, System.currentTimeMillis(), e.extra?.split('|')?.getOrNull(2) ?: "")
        io.launch {
            val ok = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))!!.use { input ->
                    file(item).outputStream().use { input.copyTo(it) }
                }
                val size = file(item).length()
                if (size <= 0) throw java.io.IOException("empty file")
                items = items + item.copy(size = size)
                save()
            }.isSuccess
            if (!ok) file(item).delete()
            else if (deleteOriginal && deleteFile(Uri.parse(uri))) {
                // The row stays while its file does, so the downloads list keeps showing what is on the device.
                runCatching { Db.get(context).io { deleteDownload(e.id) } }
            }
            main.post { done(ok) }
            refresh()
        }
    }

    // Back to the downloads list: a new file appears in the public Downloads folder and in the gallery.
    fun restore(item: Item, done: (Boolean) -> Unit) {
        io.launch {
            val cr = context.contentResolver
            val uri = runCatching {
                val target = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                    put(MediaStore.Downloads.MIME_TYPE, item.mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }) ?: throw java.io.IOException("no target")
                try {
                    file(item).inputStream().use { input ->
                        cr.openOutputStream(target)!!.use { output -> input.copyTo(output) }
                    }
                    cr.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    runCatching { cr.delete(target, null, null) }
                    throw e
                }
                target
            }.getOrNull()
            if (uri != null) {
                // The file is back in Downloads either way; only its row in the list may be missing.
                runCatching {
                    Db.get(context).io {
                        val id = addDownload(item.name, item.mime, item.source)
                        finishDownload(id, uri.toString(), DownloadService.DONE, item.size)
                    }
                }
                remove(item)
            }
            main.post { done(uri != null) }
            refresh()
        }
    }

    fun delete(item: Item, done: () -> Unit = {}) {
        io.launch {
            remove(item)
            main.post(done)
            refresh()
        }
    }

    // A file saved where the user chose is a document (SAF), which ContentResolver.delete does not remove.
    private fun deleteFile(uri: Uri): Boolean = runCatching {
        val cr = context.contentResolver
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(cr, uri)
        else cr.delete(uri, null, null) > 0
    }.getOrDefault(false)

    // The private copy goes with the entry: nothing hidden stays behind after a restore or a delete.
    private fun remove(item: Item) {
        items = items.filter { it.id != item.id }
        save()
        file(item).delete()
    }
}
