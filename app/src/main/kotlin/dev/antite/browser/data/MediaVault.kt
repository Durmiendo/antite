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
import dev.antite.browser.lang.catching
import dev.antite.browser.service.DownloadService
import dev.antite.browser.service.Downloads
import dev.antite.keys.Cipher256
import dev.antite.keys.Disk
import dev.antite.keys.KeyRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

// Files taken out of the downloads list, downloaded straight into it or added from other apps: the file lives in the
// app's private folder, encrypted (VaultFile), so other apps and the gallery do not see
// it. Every file has its own key; names, folders and those keys live in the index, sealed by the vault data key of the
// key ring. Deleting a file replaces the data key, so the file's key is gone for good with the old one.
// What reads a file of the storage, for the interface: it needs no part of the key ring for that.
typealias VaultReader = dev.antite.keys.VaultFile.Reader

class MediaVault(
    private val context: Context,
    dir: File = File(context.filesDir, "vault"),
    ring: KeyRing = Secure.ring(context),
    legacyUnwrap: (ByteArray) -> ByteArray = Crypto::open,
    cipher: () -> Cipher256 = { Secure.cipher(context) },
    zeros: () -> Boolean = { Secure.wipeZeros(context) },
    private val legacyMoved: () -> Unit = { Secure.legacyMoved(context, "vault") },
    disk: Disk = Secure.disk(context),
) {
    companion object {
        @Volatile
        private var instance: MediaVault? = null

        // One per process: two on the same folder would tidy away each other's unfinished files.
        fun get(context: Context): MediaVault =
            instance ?: synchronized(this) { instance ?: MediaVault(context.applicationContext).also { instance = it } }

        private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_").take(100).ifEmpty { "file" }
    }

    data class Item(
        val id: VaultFileId, val name: String, val mime: String, val size: Long, val time: Long, val source: String,
        val folder: VaultFolderId? = null,
    )

    // Folders nest: parent is the folder it sits in, null for the top.
    data class Folder(val id: VaultFolderId, val name: String, val parent: VaultFolderId? = null)

    private val core = VaultCore(disk, dir, ring, legacyUnwrap, cipher, zeros, { m, e -> Log.w("Antite", m, e) })

    // One worker: every operation rewrites the whole index, so two at once would lose each other's entries.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())

    var items by mutableStateOf(emptyList<Item>())
        private set
    var folders by mutableStateOf(emptyList<Folder>())
        private set
    // Whether the index is read: with a password the vault stays closed until the key ring is unlocked.
    var opened by mutableStateOf(false)
        private set
    // Files still waiting for their encryption or for the new cipher; they cannot be opened meanwhile.
    var pending by mutableStateOf(emptySet<VaultFileId>())
        private set
    val revision = mutableIntStateOf(0)

    init {
        if (ring.unlocked) catching { open() }.onFailure { Log.w("Antite", "vault not opened", it) }
    }

    // Reads the index; the key ring must be unlocked.
    @Synchronized
    fun open() {
        if (opened) return
        val joined = core.open()
        opened = true
        show()
        if (joined) io.launch { catching { core.save() }.onFailure { Log.w("Antite", "vault index not saved", it) } }
        pending = items.filter { core.needsWork(it) }.map { it.id }.toSet()
        io.launch { moveOver() }
        refresh()
    }

    // Forgets the index and wipes the file keys; used when the key ring locks.
    @Synchronized
    fun close() {
        VaultShare.revokeAll(context)
        core.close()
        show()
        pending = emptySet()
        opened = false
        refresh()
    }

    // The core's lists, as the interface sees them.
    private fun show() {
        items = core.items
        folders = core.folders
    }

    private fun refresh() {
        main.post { revision.intValue++ }
    }

    fun reader(item: Item) = core.reader(item)

    fun thumb(item: Item): ByteArray? = core.thumb(item)

    fun saveThumb(item: Item, jpeg: ByteArray) {
        catching { core.saveThumb(item, jpeg) }.onFailure { Log.w("Antite", "vault preview not saved", it) }
    }

    // A download straight into the vault: the entry is made up front, its file is written by the download and the
    // entry joins the index only once the file is complete.
    fun reserve(name: String, mime: String, source: String): Item {
        if (!opened) throw KeyRing.LockedException()
        return core.reserve(Item(core.newFileId(), sanitize(name), mime, 0, System.currentTimeMillis(), source))
    }

    fun output(item: Item): OutputStream = core.output(item)

    fun discard(item: Item) {
        io.launch { catching { core.discard(item) }.onFailure { Log.w("Antite", "vault download not discarded", it) } }
    }

    fun receive(item: Item, size: Long) {
        io.launch {
            catching { core.receive(item, size) }.onFailure { Log.w("Antite", "vault index not saved", it) }
            show()
            refresh()
        }
    }

    private fun moveOver() {
        val clean = core.moveOver { id -> pending = pending - id }
        if (clean) legacyMoved()
        show()
        refresh()
    }

    // Puts every file under the given cipher, one at a time, then replaces the data key so the old file keys die.
    // Whether a file of the open vault is still sealed with another cipher than target.
    fun waitsForCipher(target: Cipher256): Boolean = items.any { core.cipherOf(it).let { c -> c != null && c != target } }

    fun recrypt(target: Cipher256, done: () -> Unit = {}) {
        io.launch {
            val todo = items.filter { core.cipherOf(it).let { c -> c != null && c != target } }
            pending = pending + todo.map { it.id }
            for (item in todo) {
                catching { core.recrypt(item, target) }.onFailure { Log.w("Antite", "vault file ${item.id} not re-encrypted", it) }
                show()
                pending = pending - item.id
                refresh()
            }
            catching { core.saveRotated() }.onFailure { Log.w("Antite", "vault key not replaced", it) }
            main.post(done)
            refresh()
        }
    }

    private fun store(input: InputStream, item: Item) {
        try {
            core.store(input, core.begin(item))
        } finally {
            show()
        }
    }

    // A download entry moves in: the file is copied and indexed, only then the original (if asked) goes away
    // together with its downloads row.
    fun add(e: Entry, deleteOriginal: Boolean, done: (Boolean) -> Unit) {
        val uri = e.url ?: return done(false)
        val mime = Downloads.mime(e).takeIf { it != "*/*" } ?: "application/octet-stream"
        if (!opened) return done(false)
        val item = Item(core.newFileId(), sanitize(e.title ?: "file"), mime, 0, System.currentTimeMillis(), Downloads.source(e))
        io.launch {
            val ok = catching { store(context.contentResolver.openInputStream(Uri.parse(uri))!!, item) }
                .onFailure { Log.w("Antite", "vault add failed", it) }.isSuccess
            if (ok && deleteOriginal && deleteFile(Uri.parse(uri))) {
                // The row stays while its file does, so the downloads list keeps showing what is on the device.
                catching { Db.get(context).io { deleteDownload(e.id) } }.onFailure { Log.w("Antite", "download row not removed", it) }
            }
            main.post { done(ok) }
            refresh()
        }
    }

    // Files picked in the system file chooser or shared by another app. An original is deleted only where asked and
    // where its provider lets us; a shared file is never ours to delete.
    fun import(uris: List<Uri>, folder: VaultFolderId?, deleteOriginals: Boolean, done: (added: Int, failed: Int) -> Unit) {
        if (!opened) return done(0, uris.size)
        io.launch {
            var added = 0
            val cr = context.contentResolver
            for (uri in uris) {
                val name = catching {
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment ?: "file"
                val item = Item(core.newFileId(), sanitize(name), cr.getType(uri) ?: "application/octet-stream", 0,
                    System.currentTimeMillis(), "", folder?.takeIf { id -> folders.any { it.id == id } })
                val ok = catching { store(cr.openInputStream(uri)!!, item) }
                    .onFailure { Log.w("Antite", "vault import failed", it) }.isSuccess
                if (ok) {
                    added++
                    if (deleteOriginals) deleteFile(uri)
                }
            }
            main.post { done(added, uris.size - added) }
            refresh()
        }
    }

    // Back to the downloads list: a new file appears in the public Downloads folder and in the gallery.
    // Back to the downloads list: a new file appears in the public Downloads folder and in the gallery. The copies
    // that made it out leave the storage together, under one new data key; done gets how many did.
    fun restore(items: List<Item>, done: (Int) -> Unit) {
        io.launch {
            val back = items.filter { copyOut(it) }
            catching { remove(back) }.onFailure { Log.w("Antite", "vault files not removed after restore", it) }
            main.post { done(back.size) }
            refresh()
        }
    }

    private suspend fun copyOut(item: Item): Boolean {
        val cr = context.contentResolver
        val uri = catching {
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
                catching { cr.delete(target, null, null) }
                throw e
            }
            target
        }.onFailure { Log.w("Antite", "vault file ${item.id} not restored", it) }.getOrNull() ?: return false
        // The file is back in Downloads either way; only its row in the list may be missing.
        catching {
            Db.get(context).io {
                val id = addDownload(item.name, item.mime, item.source)
                finishDownload(id, uri.toString(), DownloadService.DONE, item.size)
            }
        }.onFailure { Log.w("Antite", "download row of restored ${item.id} not added", it) }
        return true
    }

    fun delete(items: List<Item>, done: () -> Unit = {}) {
        io.launch {
            catching { remove(items) }.onFailure { Log.w("Antite", "vault files not deleted", it) }
            main.post(done)
            refresh()
        }
    }

    fun move(items: List<Item>, folder: VaultFolderId?) {
        val ids = items.map { it.id }.toSet()
        change { core.items = core.items.map { if (it.id in ids) it.copy(folder = folder) else it } }
    }

    fun rename(item: Item, name: String) =
        change { core.items = core.items.map { if (it.id == item.id) it.copy(name = sanitize(name)) else it } }

    fun addFolder(name: String, parent: VaultFolderId? = null, done: (Folder) -> Unit = {}) {
        val f = Folder(core.newFolderId(), sanitize(name), parent?.takeIf { id -> folders.any { it.id == id } })
        change({ done(f) }) { core.folders = core.folders + f }
    }

    fun renameFolder(f: Folder, name: String) =
        change { core.folders = core.folders.map { if (it.id == f.id) it.copy(name = sanitize(name)) else it } }

    fun deleteFolder(f: Folder) = change {
        val (fs, its) = VaultIndex.deleteFolder(core.folders, core.items, f)
        core.folders = fs
        core.items = its
    }

    fun moveFolder(f: Folder, parent: VaultFolderId?) {
        if (!VaultIndex.canMove(folders, f, parent)) return
        change { core.folders = core.folders.map { if (it.id == f.id) it.copy(parent = parent) else it } }
    }

    fun path(id: VaultFolderId?): List<Folder> = VaultIndex.path(folders, id)

    private fun change(done: () -> Unit = {}, block: () -> Unit) {
        io.launch {
            block()
            catching { core.save() }.onFailure { Log.w("Antite", "vault index not saved", it) }
            show()
            main.post(done)
            refresh()
        }
    }

    // A file saved where the user chose is a document (SAF), which ContentResolver.delete does not remove.
    private fun deleteFile(uri: Uri): Boolean = catching {
        val cr = context.contentResolver
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(cr, uri)
        else cr.delete(uri, null, null) > 0
    }.getOrDefault(false)

    private fun remove(items: List<Item>) {
        try {
            core.removeAll(items)
        } finally {
            show()
        }
    }
}
