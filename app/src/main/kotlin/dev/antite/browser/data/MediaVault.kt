package dev.antite.browser.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.antite.browser.service.DownloadService
import dev.antite.browser.service.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection

// Files taken out of the downloads list, downloaded straight into it or added from other apps: the file lives in the
// app's private folder, encrypted (VaultFile, docs/decisions/0006), so other apps and the gallery do not see it. The
// index is one more encrypted file next to them; names, folders and metadata live there, a file is named after its id.
class MediaVault(
    private val context: Context,
    private val dir: File = File(context.filesDir, "vault"),
    private val keys: VaultFile.Keys = Crypto.fileKeys,
) {
    companion object {
        private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_").take(100).ifEmpty { "file" }
    }

    data class Item(
        val id: Long, val name: String, val mime: String, val size: Long, val time: Long, val source: String,
        val folder: Long? = null,
    )

    data class Folder(val id: Long, val name: String)

    // One worker: every operation rewrites the whole index, so two at once would lose each other's entries.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())
    private val index = File(dir, "index.enc")
    private val oldIndex = File(dir, "index.json")

    var items by mutableStateOf(emptyList<Item>())
        private set
    var folders by mutableStateOf(emptyList<Folder>())
        private set
    // Files from before the encryption that are still waiting for it; they cannot be opened meanwhile.
    var pending by mutableStateOf(emptySet<Long>())
        private set
    val revision = mutableIntStateOf(0)
    private var nextId: Long
    // An index that could not be read is never written over: the files it lists would be lost for good.
    private var unreadable = false

    init {
        dir.mkdirs()
        load()
        if (!unreadable) adoptStrays()
        nextId = maxOf(items.maxOfOrNull { it.id } ?: 0, folders.maxOfOrNull { it.id } ?: 0) + 1
        pending = items.filter { !file(it).exists() && oldFile(it).exists() }.map { it.id }.toSet()
        if (pending.isNotEmpty() || oldIndex.exists()) io.launch { encryptOld() }
    }

    // An open file missing from the index (an older version could lose its entry) would never be encrypted: it is
    // taken in under a made-up name and encrypted with the rest.
    private fun adoptStrays() {
        val known = items.map { it.id }.toSet()
        val strays = dir.listFiles().orEmpty().mapNotNull { f -> f.name.toLongOrNull()?.takeIf { it !in known }?.let { it to f } }
        items = items + strays.map { (id, f) ->
            val mime = runCatching { f.inputStream().buffered().use { URLConnection.guessContentTypeFromStream(it) } }.getOrNull()
            Item(id, "recovered-$id", mime ?: "application/octet-stream", f.length(), f.lastModified(), "")
        }
    }

    private fun file(item: Item) = File(dir, "${item.id}.enc")

    private fun oldFile(item: Item) = File(dir, item.id.toString())

    fun reader(item: Item) = VaultFile.Reader(file(item), keys)

    // A download straight into the vault: the entry is made up front, its file is written by the download and the
    // entry joins the index only once the file is complete.
    fun reserve(name: String, mime: String, source: String) =
        Item(nextId++, sanitize(name), mime, 0, System.currentTimeMillis(), source)

    fun output(item: Item): OutputStream = VaultFile.writer(file(item).outputStream(), keys)

    fun discard(item: Item) {
        file(item).delete()
    }

    fun receive(item: Item, size: Long) {
        io.launch {
            items = items + item.copy(size = size)
            save()
            refresh()
        }
    }

    private fun load() {
        runCatching {
            if (index.exists()) {
                val o = JSONObject(String(VaultFile.Reader(index, keys).stream().use { it.readBytes() }))
                items = parseItems(o.getJSONArray("items"))
                val f = o.getJSONArray("folders")
                folders = (0 until f.length()).map { i -> f.getJSONObject(i).let { Folder(it.getLong("id"), it.getString("name")) } }
            } else if (oldIndex.exists()) {
                items = parseItems(JSONArray(oldIndex.readText()))
            }
        }.onFailure {
            Log.w("Antite", "vault index unreadable", it)
            unreadable = true
        }
    }

    private fun parseItems(a: JSONArray): List<Item> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Item(o.getLong("id"), o.getString("name"), o.getString("mime"), o.getLong("size"),
            o.getLong("time"), o.optString("source"), if (o.has("folder")) o.getLong("folder") else null)
    }

    // Written aside and renamed: a failure in the middle leaves the previous index.
    private fun save() {
        if (unreadable) throw java.io.IOException("the vault index could not be read")
        val a = JSONArray()
        items.forEach { i ->
            a.put(JSONObject()
                .put("id", i.id).put("name", i.name).put("mime", i.mime)
                .put("size", i.size).put("time", i.time).put("source", i.source)
                .apply { i.folder?.let { put("folder", it) } })
        }
        val f = JSONArray()
        folders.forEach { f.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val tmp = File(dir, "index.enc.tmp")
        VaultFile.writer(tmp.outputStream(), keys).use { it.write(JSONObject().put("items", a).put("folders", f).toString().toByteArray()) }
        if (!tmp.renameTo(index)) throw java.io.IOException("cannot replace the vault index")
        oldIndex.delete()
    }

    private fun refresh() {
        main.post { revision.intValue++ }
    }

    // The encrypted copy is complete once it has its final name; only then the open file goes away, so stopping at
    // any step loses nothing.
    private fun encryptOld() {
        for (item in items) {
            val old = oldFile(item)
            if (!old.exists()) continue
            runCatching {
                if (!file(item).exists()) {
                    val tmp = File(dir, "${item.id}.enc.tmp")
                    old.inputStream().use { input -> VaultFile.writer(tmp.outputStream(), keys).use { input.copyTo(it) } }
                    if (!tmp.renameTo(file(item))) throw java.io.IOException("cannot rename ${tmp.name}")
                }
                old.delete()
            }.onFailure { Log.w("Antite", "vault file ${item.id} not encrypted", it) }
            pending = pending - item.id
        }
        runCatching { save() }.onFailure { Log.w("Antite", "vault index not encrypted", it) }
        refresh()
    }

    private fun store(input: InputStream, item: Item): Item {
        input.use { src -> output(item).use { src.copyTo(it) } }
        val size = VaultFile.plainLength(file(item).length())
        if (size <= 0) throw java.io.IOException("empty file")
        return item.copy(size = size)
    }

    // A download entry moves in: the file is copied and indexed, only then the original (if asked) goes away
    // together with its downloads row.
    fun add(e: Entry, deleteOriginal: Boolean, done: (Boolean) -> Unit) {
        val uri = e.url ?: return done(false)
        val mime = Downloads.mime(e).takeIf { it != "*/*" } ?: "application/octet-stream"
        val item = Item(nextId++, sanitize(e.title ?: "file"), mime, 0, System.currentTimeMillis(), Downloads.source(e))
        io.launch {
            val ok = runCatching {
                items = items + store(context.contentResolver.openInputStream(Uri.parse(uri))!!, item)
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

    // Files picked in the system file chooser or shared by another app. An original is deleted only where asked and
    // where its provider lets us; a shared file is never ours to delete.
    fun import(uris: List<Uri>, folder: Long?, deleteOriginals: Boolean, done: (added: Int, failed: Int) -> Unit) {
        io.launch {
            var added = 0
            val cr = context.contentResolver
            for (uri in uris) {
                val name = runCatching {
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment ?: "file"
                val item = Item(nextId++, sanitize(name), cr.getType(uri) ?: "application/octet-stream", 0,
                    System.currentTimeMillis(), "", folder?.takeIf { id -> folders.any { it.id == id } })
                val ok = runCatching {
                    items = items + store(cr.openInputStream(uri)!!, item)
                    save()
                }.onFailure { Log.w("Antite", "vault import failed", it) }.isSuccess
                if (!ok) file(item).delete() else {
                    added++
                    if (deleteOriginals) deleteFile(uri)
                }
            }
            main.post { done(added, uris.size - added) }
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
                    reader(item).stream().use { input ->
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

    fun move(item: Item, folder: Long?) = change { items = items.map { if (it.id == item.id) it.copy(folder = folder) else it } }

    fun addFolder(name: String, done: (Folder) -> Unit = {}) {
        val f = Folder(nextId++, sanitize(name))
        change({ done(f) }) { folders = folders + f }
    }

    fun renameFolder(f: Folder, name: String) =
        change { folders = folders.map { if (it.id == f.id) it.copy(name = sanitize(name)) else it } }

    // Its files stay, without a folder.
    fun deleteFolder(f: Folder) = change {
        folders = folders.filter { it.id != f.id }
        items = items.map { if (it.folder == f.id) it.copy(folder = null) else it }
    }

    private fun change(done: () -> Unit = {}, block: () -> Unit) {
        io.launch {
            block()
            runCatching { save() }.onFailure { Log.w("Antite", "vault index not saved", it) }
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
        oldFile(item).delete()
    }
}
