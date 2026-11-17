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
import java.nio.ByteBuffer

// Files taken out of the downloads list, downloaded straight into it or added from other apps: the file lives in the
// app's private folder, encrypted (VaultFile), so other apps and the gallery do not see
// it. Every file has its own key; names, folders and those keys live in the index, sealed by the vault data key of the
// key ring. Deleting a file replaces the data key, so the file's key is gone for good with the old one.
class MediaVault(
    private val context: Context,
    private val dir: File = File(context.filesDir, "vault"),
    private val ring: KeyRing = Secure.ring(context),
    private val legacyUnwrap: (ByteArray) -> ByteArray = Crypto::open,
    private val cipher: () -> Cipher256 = { Secure.cipher(context) },
    private val zeros: () -> Boolean = { Secure.wipeZeros(context) },
    private val legacyMoved: () -> Unit = { Secure.legacyMoved(context, "vault") },
) {
    companion object {
        @Volatile
        private var instance: MediaVault? = null

        // One per process: two on the same folder would tidy away each other's unfinished files.
        fun get(context: Context): MediaVault =
            instance ?: synchronized(this) { instance ?: MediaVault(context.applicationContext).also { instance = it } }

        private const val QUIET_MS = 10 * 60_000L

        private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_").take(100).ifEmpty { "file" }
        private val INDEX_AAD = "antite-vault-index".toByteArray()
    }

    data class Item(
        val id: Long, val name: String, val mime: String, val size: Long, val time: Long, val source: String,
        val folder: Long? = null,
    )

    // Folders nest: parent is the folder it sits in, null for the top.
    data class Folder(val id: Long, val name: String, val parent: Long? = null)

    // One worker: every operation rewrites the whole index, so two at once would lose each other's entries.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())
    private val index = File(dir, "index.box")
    private val v1Index = File(dir, "index.enc")
    private val oldIndex = File(dir, "index.json")

    var items by mutableStateOf(emptyList<Item>())
        private set
    var folders by mutableStateOf(emptyList<Folder>())
        private set
    // Whether the index is read: with a password the vault stays closed until the key ring is unlocked.
    var opened by mutableStateOf(false)
        private set
    // Files still waiting for their encryption or for the new cipher; they cannot be opened meanwhile.
    var pending by mutableStateOf(emptySet<Long>())
        private set
    val revision = mutableIntStateOf(0)
    private val fileKeys = HashMap<Long, ByteArray>()
    // Downloads straight into the vault that are still being written, and those finished while it was closed: their
    // keys outlive close(), the finished ones join the index at the next open().
    private val inFlight = HashSet<Long>()
    private val finished = ArrayList<Item>()
    private var nextId = 1L
    // An index that could not be read is never written over: the files it lists would be lost for good.
    private var unreadable = false

    init {
        dir.mkdirs()
        if (ring.unlocked) runCatching { open() }.onFailure { Log.w("Antite", "vault not opened", it) }
    }

    // Reads the index; the key ring must be unlocked.
    @Synchronized
    fun open() {
        if (opened) return
        ring.recover(KeyRing.VAULT) { k -> runCatching { readIndex(k) }.isSuccess }
        unreadable = false
        runCatching {
            when {
                index.exists() -> readIndex(ring.key(KeyRing.VAULT)).let(::apply)
                v1Index.exists() -> apply(readV1Index())
                oldIndex.exists() -> items = parseItems(JSONArray(oldIndex.readText()))
            }
        }.onFailure {
            Log.w("Antite", "vault index unreadable", it)
            unreadable = true
        }
        if (!unreadable) {
            adoptStrays()
            dropOrphans()
        }
        nextId = maxOf(nextId, ((items + finished).maxOfOrNull { it.id } ?: 0) + 1, (folders.maxOfOrNull { it.id } ?: 0) + 1)
        opened = true
        if (finished.isNotEmpty()) {
            items = items + finished
            finished.clear()
            io.launch { runCatching { save() }.onFailure { Log.w("Antite", "vault index not saved", it) } }
        }
        pending = items.filter { needsWork(it) }.map { it.id }.toSet()
        io.launch { moveOver() }
        refresh()
    }

    // Forgets the index and wipes the file keys; used when the key ring locks.
    @Synchronized
    fun close() {
        synchronized(fileKeys) {
            val keep = inFlight + finished.map { it.id }
            fileKeys.keys.filter { it !in keep }.forEach { fileKeys.remove(it)?.fill(0) }
        }
        items = emptyList()
        folders = emptyList()
        pending = emptySet()
        opened = false
        refresh()
    }

    private class Index(val items: List<Item>, val folders: List<Folder>, val keys: Map<Long, ByteArray>)

    private fun apply(i: Index) {
        items = i.items
        folders = i.folders
        synchronized(fileKeys) { fileKeys.putAll(i.keys) }
    }

    // The sealed index: the length of the JSON, the JSON, then id and key of every file. Keys stay out of the JSON,
    // so they never become strings that cannot be wiped.
    private fun readIndex(dataKey: ByteArray): Index {
        val plain = Ciphers.unbox(dataKey, index.readBytes(), INDEX_AAD)
        try {
            val buf = ByteBuffer.wrap(plain)
            val json = ByteArray(buf.int).also { buf.get(it) }
            val o = JSONObject(String(json))
            json.fill(0)
            val keys = HashMap<Long, ByteArray>()
            while (buf.remaining() >= 8 + Cipher256.KEY) keys[buf.long] = ByteArray(Cipher256.KEY).also { buf.get(it) }
            return Index(parseItems(o.getJSONArray("items")), parseFolders(o.getJSONArray("folders")), keys)
        } finally {
            plain.fill(0)
        }
    }

    // The version 1 index: a version 1 vault file, its key wrapped by the old device key.
    private fun readV1Index(): Index {
        val k = VaultFile.v1Key(VaultFile.head(v1Index), legacyUnwrap)
        try {
            val o = JSONObject(String(VaultFile.Reader(v1Index, k).stream().use { it.readBytes() }))
            return Index(parseItems(o.getJSONArray("items")), parseFolders(o.getJSONArray("folders")), emptyMap())
        } finally {
            k.fill(0)
        }
    }

    private fun parseItems(a: JSONArray): List<Item> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Item(o.getLong("id"), o.getString("name"), o.getString("mime"), o.getLong("size"),
            o.getLong("time"), o.optString("source"), if (o.has("folder")) o.getLong("folder") else null)
    }

    private fun parseFolders(a: JSONArray): List<Folder> =
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Folder(it.getLong("id"), it.getString("name"), if (it.has("parent")) it.getLong("parent") else null) }
        }

    private fun indexBytes(): ByteArray {
        val a = JSONArray()
        items.forEach { i ->
            a.put(JSONObject()
                .put("id", i.id).put("name", i.name).put("mime", i.mime)
                .put("size", i.size).put("time", i.time).put("source", i.source)
                .apply { i.folder?.let { put("folder", it) } })
        }
        val f = JSONArray()
        folders.forEach { f.put(JSONObject().put("id", it.id).put("name", it.name).apply { it.parent?.let { p -> put("parent", p) } }) }
        val json = JSONObject().put("items", a).put("folders", f).toString().toByteArray()
        val keys = synchronized(fileKeys) { items.mapNotNull { i -> fileKeys[i.id]?.let { i.id to it } } }
        val out = ByteBuffer.allocate(4 + json.size + keys.size * (8 + Cipher256.KEY)).putInt(json.size).put(json)
        keys.forEach { (id, k) -> out.putLong(id).put(k) }
        json.fill(0)
        return out.array()
    }

    // Written aside and renamed: a failure in the middle leaves the previous index.
    private fun save(dataKey: ByteArray = ring.key(KeyRing.VAULT)) {
        if (!opened) throw KeyRing.LockedException()
        if (unreadable) throw java.io.IOException("the vault index could not be read")
        val plain = indexBytes()
        val tmp = File(dir, "index.box.tmp")
        try {
            tmp.outputStream().use { out ->
                out.write(Ciphers.box(cipher(), dataKey, plain, INDEX_AAD))
                out.fd.sync()
            }
        } finally {
            plain.fill(0)
        }
        if (!tmp.renameTo(index)) throw java.io.IOException("cannot replace the vault index")
        oldIndex.delete()
    }

    // A file's key goes away with a new data key: the index that held it can never be opened again.
    private fun saveRotated() {
        ring.rotate(KeyRing.VAULT) { _, fresh -> save(fresh) }
    }

    private fun refresh() {
        main.post { revision.intValue++ }
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

    private fun needsWork(item: Item): Boolean {
        if (oldFile(item).exists()) return true
        val f = file(item)
        if (!f.exists()) return false
        val head = runCatching { VaultFile.head(f) }.getOrNull() ?: return false
        return head.version == 1 || synchronized(fileKeys) { item.id !in fileKeys }
    }

    private fun keyOf(item: Item): ByteArray =
        synchronized(fileKeys) { fileKeys[item.id] } ?: throw java.io.IOException("no key for vault file ${item.id}")

    fun reader(item: Item) = VaultFile.Reader(file(item), keyOf(item))

    // A small preview, encrypted with the file's own key next to it, so the grid does not decrypt whole files.
    private fun thumbFile(item: Item) = File(dir, "${item.id}.thumb")

    fun thumb(item: Item): ByteArray? = runCatching {
        thumbFile(item).takeIf { it.exists() }?.let { f -> VaultFile.Reader(f, keyOf(item)).stream().use { it.readBytes() } }
    }.getOrNull()

    fun saveThumb(item: Item, jpeg: ByteArray) {
        runCatching {
            val tmp = File(dir, "${item.id}.thumb.tmp")
            VaultFile.writer(tmp.outputStream(), cipher(), keyOf(item)).use { it.write(jpeg) }
            if (!tmp.renameTo(thumbFile(item))) tmp.delete()
        }
    }

    private fun newKey(item: Item): ByteArray =
        Ciphers.randomBytes(Cipher256.KEY).also { synchronized(fileKeys) { fileKeys[item.id] = it } }

    private fun forgetKey(id: Long) {
        synchronized(fileKeys) { fileKeys.remove(id)?.fill(0) }
    }

    // A download straight into the vault: the entry is made up front, its file is written by the download and the
    // entry joins the index only once the file is complete.
    fun reserve(name: String, mime: String, source: String): Item {
        if (!opened) throw KeyRing.LockedException()
        return Item(nextId++, sanitize(name), mime, 0, System.currentTimeMillis(), source).also {
            newKey(it)
            synchronized(this) { inFlight += it.id }
        }
    }

    fun output(item: Item): OutputStream = VaultFile.writer(file(item).outputStream(), cipher(), keyOf(item))

    fun discard(item: Item) {
        io.launch {
            synchronized(this@MediaVault) { inFlight -= item.id }
            erase(file(item))
            forgetKey(item.id)
        }
    }

    fun receive(item: Item, size: Long) {
        io.launch {
            synchronized(this@MediaVault) {
                inFlight -= item.id
                if (!opened) return@launch Unit.also { finished += item.copy(size = size) }
                items = items + item.copy(size = size)
            }
            runCatching { save() }.onFailure { Log.w("Antite", "vault index not saved", it) }
            refresh()
        }
    }

    private fun erase(f: File) {
        if (zeros()) VaultFile.wipe(f) else f.delete()
    }

    // From version 1 to the key ring, each step durable before the next, so a stop anywhere loses nothing:
    // version 1 keys go into the new index before their headers are rewritten; an open file is encrypted under its
    // final name before it is erased. The old device key is no longer needed after that.
    private fun moveOver() {
        val v1 = items.filter { file(it).exists() && runCatching { VaultFile.head(file(it)).version == 1 }.getOrDefault(false) }
        for (item in v1) {
            if (synchronized(fileKeys) { item.id in fileKeys }) continue
            runCatching { synchronized(fileKeys) { fileKeys[item.id] = VaultFile.v1Key(VaultFile.head(file(item)), legacyUnwrap) } }
                .onFailure { Log.w("Antite", "vault file ${item.id} key unreadable", it) }
        }
        if (runCatching { save() }.onFailure { Log.w("Antite", "vault index not moved over", it) }.isFailure) return
        v1Index.delete()
        for (item in v1) runCatching { VaultFile.upgradeHeader(file(item)) }
        pending = pending - v1.map { it.id }.toSet()
        for (item in items) {
            val old = oldFile(item)
            if (!old.exists()) continue
            runCatching {
                val tmp = File(dir, "${item.id}.enc.tmp")
                val key = newKey(item)
                old.inputStream().use { input -> VaultFile.writer(tmp.outputStream(), cipher(), key).use { input.copyTo(it) } }
                if (!tmp.renameTo(file(item))) throw java.io.IOException("cannot rename ${tmp.name}")
                save()
                VaultFile.wipe(old)
            }.onFailure { Log.w("Antite", "vault file ${item.id} not encrypted", it) }
            pending = pending - item.id
        }
        if (!v1Index.exists() && items.none { runCatching { VaultFile.head(file(it)).version == 1 }.getOrDefault(false) }) {
            legacyMoved()
        }
        refresh()
    }

    // Puts every file under the given cipher, one at a time, then replaces the data key so the old file keys die.
    // A file is re-encrypted under a new id: the index switches to it in one write, so a stop leaves either the old
    // or the new file listed, and the other is an orphan for dropOrphans().
    fun recrypt(target: Cipher256, done: () -> Unit = {}) {
        io.launch {
            val todo = items.filter { runCatching { VaultFile.head(file(it)).cipher != target }.getOrDefault(false) }
            pending = pending + todo.map { it.id }
            for (item in todo) {
                runCatching {
                    val moved = item.copy(id = nextId++)
                    val key = newKey(moved)
                    reader(item).stream().use { input -> VaultFile.writer(file(moved).outputStream(), target, key).use { input.copyTo(it) } }
                    items = items.map { if (it.id == item.id) moved else it }
                    save()
                    forgetKey(item.id)
                    erase(file(item))
                    erase(thumbFile(item))
                }.onFailure { Log.w("Antite", "vault file ${item.id} not re-encrypted", it) }
                pending = pending - item.id
                refresh()
            }
            runCatching { saveRotated() }.onFailure { Log.w("Antite", "vault key not replaced", it) }
            main.post(done)
            refresh()
        }
    }

    // Encrypted files no entry points to: left by a stop between writing a file and the index, or the other way.
    // A recent one may still be in work.
    private fun dropOrphans() {
        val known = items.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        for (f in dir.listFiles().orEmpty()) {
            if (now - f.lastModified() < QUIET_MS) continue
            val id = f.name.removeSuffix(".enc").removeSuffix(".thumb").removeSuffix(".enc.tmp").toLongOrNull()
            val busy = synchronized(this) { id != null && (id in inFlight || finished.any { it.id == id }) }
            if (busy) continue
            if (f.name.endsWith(".tmp") || ((f.name.endsWith(".enc") || f.name.endsWith(".thumb")) && id != null && id !in known)) erase(f)
        }
    }

    private fun store(input: InputStream, item: Item): Item {
        input.use { src -> output(item).use { src.copyTo(it) } }
        val size = VaultFile.plainLength(file(item).length(), VaultFile.head(file(item)).cipher)
        if (size <= 0) throw java.io.IOException("empty file")
        return item.copy(size = size)
    }

    // A download entry moves in: the file is copied and indexed, only then the original (if asked) goes away
    // together with its downloads row.
    fun add(e: Entry, deleteOriginal: Boolean, done: (Boolean) -> Unit) {
        val uri = e.url ?: return done(false)
        val mime = Downloads.mime(e).takeIf { it != "*/*" } ?: "application/octet-stream"
        if (!opened) return done(false)
        val item = Item(nextId++, sanitize(e.title ?: "file"), mime, 0, System.currentTimeMillis(), Downloads.source(e))
        newKey(item)
        io.launch {
            val ok = runCatching {
                items = items + store(context.contentResolver.openInputStream(Uri.parse(uri))!!, item)
                save()
            }.isSuccess
            if (!ok) drop(item)
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
        if (!opened) return done(0, uris.size)
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
                newKey(item)
                val ok = runCatching {
                    items = items + store(cr.openInputStream(uri)!!, item)
                    save()
                }.onFailure { Log.w("Antite", "vault import failed", it) }.isSuccess
                if (!ok) drop(item) else {
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

    fun addFolder(name: String, parent: Long? = null, done: (Folder) -> Unit = {}) {
        val f = Folder(nextId++, sanitize(name), parent?.takeIf { id -> folders.any { it.id == id } })
        change({ done(f) }) { folders = folders + f }
    }

    fun renameFolder(f: Folder, name: String) =
        change { folders = folders.map { if (it.id == f.id) it.copy(name = sanitize(name)) else it } }

    // Its files and folders stay, one level up.
    fun deleteFolder(f: Folder) = change {
        folders = folders.filter { it.id != f.id }.map { if (it.parent == f.id) it.copy(parent = f.parent) else it }
        items = items.map { if (it.folder == f.id) it.copy(folder = f.parent) else it }
    }

    fun moveFolder(f: Folder, parent: Long?) {
        // Not into itself or one of its own folders.
        var p = parent
        while (p != null) {
            if (p == f.id) return
            p = folders.firstOrNull { it.id == p }?.parent
        }
        change { folders = folders.map { if (it.id == f.id) it.copy(parent = parent) else it } }
    }

    // The folder and its parents up to the top, for a path line.
    fun path(id: Long?): List<Folder> {
        val out = ArrayList<Folder>()
        var cur = id
        while (cur != null && out.size < 64) {
            val f = folders.firstOrNull { it.id == cur } ?: break
            out.add(0, f)
            cur = f.parent
        }
        return out
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

    private fun drop(item: Item) {
        items = items.filter { it.id != item.id }
        forgetKey(item.id)
        erase(file(item))
    }

    // The private copy goes with the entry: nothing hidden stays behind after a restore or a delete. The index is
    // written under a new data key, so the file's key cannot be read back from any old copy of the index.
    private fun remove(item: Item) {
        items = items.filter { it.id != item.id }
        forgetKey(item.id)
        saveRotated()
        erase(file(item))
        erase(thumbFile(item))
        erase(oldFile(item))
    }
}
