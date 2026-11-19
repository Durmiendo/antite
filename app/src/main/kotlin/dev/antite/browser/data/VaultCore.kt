package dev.antite.browser.data

import dev.antite.browser.data.MediaVault.Folder
import dev.antite.browser.data.MediaVault.Item
import dev.antite.browser.lang.catching
import dev.antite.keys.Cipher256
import dev.antite.keys.Ciphers
import dev.antite.keys.CoreKey
import dev.antite.keys.Disk
import dev.antite.keys.KeyRing
import dev.antite.keys.VaultFile
import dev.antite.keys.deleteDurably
import dev.antite.keys.input
import dev.antite.keys.output
import dev.antite.keys.readAll
import dev.antite.keys.renameDurably
import dev.antite.keys.replace
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection

// The secure storage on the disk, without Android: the index, the encrypted files and every step that changes them,
// each one made durable before the next, so a stop anywhere leaves what was there or what was asked for. MediaVault
// runs these on its worker and shows the result.
class VaultCore(
    private val disk: Disk,
    private val dir: File,
    private val ring: KeyRing,
    private val legacyUnwrap: (ByteArray) -> ByteArray,
    private val cipher: () -> Cipher256,
    private val zeros: () -> Boolean,
    private val warn: (String, Throwable) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val QUIET_MS = 10 * 60_000L
        private val INDEX_AAD = "antite-vault-index".toByteArray()
    }

    private val index = File(dir, "index.box")
    private val v1Index = File(dir, "index.enc")
    private val oldIndex = File(dir, "index.json")

    @Volatile
    var items = emptyList<Item>()
    @Volatile
    var folders = emptyList<Folder>()
    @Volatile
    var opened = false
        private set
    // The box of each file's key under the vault's data key: what the index holds. A key is opened in the core only for
    // the length of a read or a write.
    private val fileKeys = HashMap<Long, ByteArray>()
    // Downloads straight into the vault that are still being written, and those finished while it was closed: their
    // keys outlive close(), the finished ones join the index at the next open().
    private val inFlight = HashSet<Long>()
    private val finished = ArrayList<Item>()
    private var nextId = 1L
    // An index that could not be read is never written over: the files it lists would be lost for good.
    private var unreadable = false

    init {
        disk.mkdirs(dir)
    }

    @Synchronized
    fun newId() = nextId++

    // Reads the index; the key ring must be unlocked. True when downloads finished while closed joined the index and
    // it needs saving.
    @Synchronized
    fun open(): Boolean {
        if (opened) return false
        ring.recover(KeyRing.VAULT) { k -> catching { readIndex(k) }.isSuccess }
        unreadable = false
        catching {
            when {
                disk.exists(index) -> ring.withKey(KeyRing.VAULT) { k -> apply(readIndex(k), k) }
                disk.exists(v1Index) -> apply(readV1Index(), null)
                disk.exists(oldIndex) -> items = VaultIndex.parseItems(JSONArray(String(disk.readAll(oldIndex))))
            }
        }.onFailure {
            warn("vault index unreadable", it)
            unreadable = true
        }
        if (!unreadable) {
            adoptStrays()
            dropOrphans()
        }
        nextId = maxOf(nextId, ((items + finished).maxOfOrNull { it.id } ?: 0) + 1, (folders.maxOfOrNull { it.id } ?: 0) + 1)
        opened = true
        if (finished.isEmpty()) return false
        items = items + finished
        finished.clear()
        return true
    }

    // Forgets the index and the boxes of the file keys; used when the key ring locks.
    @Synchronized
    fun close() {
        synchronized(fileKeys) {
            val keep = inFlight + finished.map { it.id }
            fileKeys.keys.retainAll(keep)
        }
        items = emptyList()
        folders = emptyList()
        opened = false
    }

    private fun aadOf(id: Long) = "antite-vault-file-$id".toByteArray()

    // A version 2 index held the file keys themselves: they go into the core, are boxed under the data key and wiped,
    // and the index is written again as version 3 right away.
    private fun apply(i: VaultIndex.Index, dataKey: CoreKey?) {
        items = i.items
        folders = i.folders
        val boxes = if (!i.raw) i.keys else i.keys.mapValues { (id, raw) ->
            CoreKey.import(raw).use { it.wrapUnder(dataKey ?: throw IOException("no data key"), cipher(), aadOf(id)) }
        }
        synchronized(fileKeys) { fileKeys.putAll(boxes) }
        if (i.raw) save(dataKey!!, force = true)
    }

    private fun readIndex(dataKey: CoreKey): VaultIndex.Index {
        val plain = Ciphers.unbox(dataKey, disk.readAll(index), INDEX_AAD)
        try {
            return VaultIndex.decode(plain)
        } finally {
            plain.fill(0)
        }
    }

    // The version 1 index: a version 1 vault file, its key wrapped by the old device key.
    private fun readV1Index(): VaultIndex.Index {
        val k = VaultFile.v1Key(VaultFile.head(disk, v1Index), legacyUnwrap)
        return VaultIndex.decodeV1(String(VaultFile.Reader(disk, v1Index, k).stream().use { it.readBytes() }))
    }


    // Under the ring's hold on the key: leaving the app cannot wipe it while the index is sealed with it.
    fun save() = ring.withKey(KeyRing.VAULT) { save(it) }

    // boxes: those of the keys under dataKey (by default the current ones); force: also before open() has finished.
    private fun save(dataKey: CoreKey, boxes: Map<Long, ByteArray> = synchronized(fileKeys) { HashMap(fileKeys) }, force: Boolean = false) {
        if (!opened && !force) throw KeyRing.LockedException()
        if (unreadable) throw IOException("the vault index could not be read")
        val plain = VaultIndex.encode(items, folders, boxes)
        val sealed = try {
            Ciphers.box(cipher(), dataKey, plain, INDEX_AAD)
        } finally {
            plain.fill(0)
        }
        disk.replace(index, sealed)
        disk.deleteDurably(oldIndex)
    }

    // A file's key goes away with a new data key: the index that held it can never be opened again. The boxes of the
    // keys that stay are made again under the new data key and written with it in one index.
    fun saveRotated() {
        var moved: Map<Long, ByteArray>? = null
        ring.rotate(KeyRing.VAULT) { old, fresh ->
            val boxes = synchronized(fileKeys) { HashMap(fileKeys) }.mapValues { (id, box) ->
                (CoreKey.unwrap(old, box, aadOf(id)) ?: throw IOException("vault file $id key damaged")).use { it.wrapUnder(fresh, cipher(), aadOf(id)) }
            }
            save(fresh, boxes)
            moved = boxes
        }
        moved?.let { m -> synchronized(fileKeys) { m.forEach { (id, box) -> if (id in fileKeys) fileKeys[id] = box } } }
    }


    // An open file missing from the index (an older version could lose its entry) would never be encrypted: it is
    // taken in under a made-up name and encrypted with the rest.
    private fun adoptStrays() {
        val known = items.map { it.id }.toSet()
        val strays = disk.list(dir).mapNotNull { f -> f.name.toLongOrNull()?.takeIf { it !in known }?.let { it to f } }
        items = items + strays.map { (id, f) ->
            val mime = catching { disk.input(f).buffered().use { URLConnection.guessContentTypeFromStream(it) } }.getOrNull()
            Item(id, "recovered-$id", mime ?: "application/octet-stream", disk.length(f), disk.modified(f), "")
        }
    }

    fun file(item: Item) = File(dir, "${item.id}.enc")

    private fun oldFile(item: Item) = File(dir, item.id.toString())

    private fun thumbFile(item: Item) = File(dir, "${item.id}.thumb")

    fun needsWork(item: Item): Boolean {
        if (disk.exists(oldFile(item))) return true
        val f = file(item)
        if (!disk.exists(f)) return false
        val head = catching { VaultFile.head(disk, f) }.getOrNull() ?: return false
        return head.version == 1 || synchronized(fileKeys) { item.id !in fileKeys }
    }

    // The file's key opened in the core; the caller closes it (a reader or a writer does it with itself).
    private fun keyOf(item: Item): CoreKey {
        val box = synchronized(fileKeys) { fileKeys[item.id] } ?: throw IOException("no key for vault file ${item.id}")
        return ring.withKey(KeyRing.VAULT) { d -> CoreKey.unwrap(d, box, aadOf(item.id)) } ?: throw IOException("vault file ${item.id} key damaged")
    }

    fun reader(item: Item) = VaultFile.Reader(disk, file(item), keyOf(item))

    fun cipherOf(item: Item): Cipher256? = catching { VaultFile.head(disk, file(item)).cipher }.getOrNull()

    // A small preview, encrypted with the file's own key next to it, so the grid does not decrypt whole files.
    fun thumb(item: Item): ByteArray? = catching {
        thumbFile(item).takeIf { disk.exists(it) }?.let { f -> VaultFile.Reader(disk, f, keyOf(item)).stream().use { it.readBytes() } }
    }.getOrNull()

    fun saveThumb(item: Item, jpeg: ByteArray) {
        val tmp = File(dir, "${item.id}.thumb.tmp")
        VaultFile.writer(disk.output(tmp, sync = true), cipher(), keyOf(item)).use { it.write(jpeg) }
        disk.renameDurably(tmp, thumbFile(item))
    }

    // A new key for the file, boxed into the index map; the caller closes the key.
    private fun newKey(item: Item): CoreKey {
        val k = CoreKey.random()
        try {
            val box = ring.withKey(KeyRing.VAULT) { d -> k.wrapUnder(d, cipher(), aadOf(item.id)) }
            synchronized(fileKeys) { fileKeys[item.id] = box }
        } catch (e: Throwable) {
            k.close()
            throw e
        }
        return k
    }

    private fun forgetKey(id: Long) {
        synchronized(fileKeys) { fileKeys.remove(id) }
    }

    // An entry for a file about to be written, with its key.
    fun begin(item: Item): Item = item.also { newKey(it).close() }

    // A download straight into the vault: the entry is made up front, its file is written by the download and the
    // entry joins the index only once the file is complete.
    @Synchronized
    fun reserve(item: Item): Item {
        if (!opened) throw KeyRing.LockedException()
        newKey(item).close()
        inFlight += item.id
        return item
    }

    // The file is on the disk when the stream is closed.
    fun output(item: Item): OutputStream = VaultFile.writer(disk.output(file(item), sync = true), cipher(), keyOf(item))

    fun discard(item: Item) {
        synchronized(this) { inFlight -= item.id }
        erase(file(item))
        forgetKey(item.id)
    }

    // A finished download joins the index; false while the vault is closed: it joins at the next open().
    fun receive(item: Item, size: Long): Boolean {
        synchronized(this) {
            inFlight -= item.id
            if (!opened) {
                finished += item.copy(size = size)
                return false
            }
            disk.syncFolder(dir)
            items = items + item.copy(size = size)
        }
        save()
        return true
    }

    private fun erase(f: File) {
        if (!disk.exists(f)) return
        if (zeros()) VaultFile.wipe(disk, f) else disk.deleteDurably(f)
    }

    // From version 1 to the key ring, each step durable before the next, so a stop anywhere loses nothing:
    // version 1 keys go into the new index before their headers are rewritten; an open file is encrypted under its
    // final name before it is erased. The old device key is no longer needed after that. done() is told of each file
    // finished; the result says whether nothing of version 1 is left.
    fun moveOver(done: (Long) -> Unit): Boolean {
        val v1 = items.filter { disk.exists(file(it)) && catching { VaultFile.head(disk, file(it)).version == 1 }.getOrDefault(false) }
        for (item in v1) {
            if (synchronized(fileKeys) { item.id in fileKeys }) continue
            catching {
                VaultFile.v1Key(VaultFile.head(disk, file(item)), legacyUnwrap).use { k ->
                    val box = ring.withKey(KeyRing.VAULT) { d -> k.wrapUnder(d, cipher(), aadOf(item.id)) }
                    synchronized(fileKeys) { fileKeys[item.id] = box }
                }
            }
                .onFailure { warn("vault file ${item.id} key unreadable", it) }
        }
        if (catching { save() }.onFailure { warn("vault index not moved over", it) }.isFailure) return false
        disk.deleteDurably(v1Index)
        for (item in v1) {
            catching { VaultFile.upgradeHeader(disk, file(item)) }.onFailure { warn("vault file ${item.id} header not upgraded", it) }
            done(item.id)
        }
        for (item in items) {
            val old = oldFile(item)
            if (!disk.exists(old)) continue
            // Its key in the index means its encrypted copy is complete: what is left is the wiping, cut short, of a
            // copy that may be half zeros by now and must not be encrypted again over the good one.
            if (synchronized(fileKeys) { item.id in fileKeys } && disk.exists(file(item))) {
                catching { VaultFile.wipe(disk, old) }.onFailure { warn("vault file ${item.id} left open", it) }
                done(item.id)
                continue
            }
            catching {
                val tmp = File(dir, "${item.id}.enc.tmp")
                val key = newKey(item)
                disk.input(old).use { input -> VaultFile.writer(disk.output(tmp, sync = true), cipher(), key).use { input.copyTo(it) } }
                disk.renameDurably(tmp, file(item))
                save()
                VaultFile.wipe(disk, old)
            }.onFailure { warn("vault file ${item.id} not encrypted", it) }
            done(item.id)
        }
        return !disk.exists(v1Index) && items.none { catching { VaultFile.head(disk, file(it)).version == 1 }.getOrDefault(false) }
    }

    // One file put under the given cipher, under a new id: the index switches to it in one write, so a stop leaves
    // either the old or the new file listed, and the other is an orphan for dropOrphans().
    fun recrypt(item: Item, target: Cipher256) {
        val moved = item.copy(id = newId())
        val key = newKey(moved)
        reader(item).stream().use { input -> VaultFile.writer(disk.output(file(moved), sync = true), target, key).use { input.copyTo(it) } }
        disk.syncFolder(dir)
        items = items.map { if (it.id == item.id) moved else it }
        save()
        forgetKey(item.id)
        erase(file(item))
        erase(thumbFile(item))
    }

    // Encrypted files no entry points to: left by a stop between writing a file and the index, or the other way.
    // A recent one may still be in work.
    private fun dropOrphans() {
        val known = items.map { it.id }.toSet()
        val t = now()
        for (f in disk.list(dir)) {
            if (t - disk.modified(f) < QUIET_MS) continue
            val id = f.name.removeSuffix(".enc").removeSuffix(".thumb").removeSuffix(".enc.tmp").toLongOrNull()
            val busy = synchronized(this) { id != null && (id in inFlight || finished.any { it.id == id }) }
            if (busy) continue
            val orphan = (f.name.endsWith(".enc") || f.name.endsWith(".thumb")) && id != null && id !in known
            if (f.name.endsWith(".tmp") || orphan) catching { erase(f) }.onFailure { warn("vault orphan ${f.name} not erased", it) }
        }
    }

    // A file from elsewhere joins the vault: written and on the disk, then indexed. On failure nothing of it stays.
    fun store(input: InputStream, item: Item): Item {
        val ok = catching {
            input.use { src -> output(item).use { src.copyTo(it) } }
            disk.syncFolder(dir)
            val size = VaultFile.plainLength(disk.length(file(item)), VaultFile.head(disk, file(item)).cipher)
            if (size <= 0) throw IOException("empty file")
            items = items + item.copy(size = size)
            save()
        }
        ok.onFailure {
            drop(item)
            throw it
        }
        return items.first { it.id == item.id }
    }

    private fun drop(item: Item) {
        items = items.filter { it.id != item.id }
        forgetKey(item.id)
        catching { erase(file(item)) }.onFailure { warn("vault file ${item.id} not erased", it) }
    }

    // The private copy goes with the entry: nothing hidden stays behind after a restore or a delete. The index is
    // written under a new data key, so the file's key cannot be read back from any old copy of the index.
    fun remove(item: Item) = removeAll(listOf(item))

    // Many at once under one new data key: one rotation however many files go.
    fun removeAll(gone: List<Item>) {
        if (gone.isEmpty()) return
        val ids = gone.map { it.id }.toSet()
        items = items.filter { it.id !in ids }
        ids.forEach(::forgetKey)
        saveRotated()
        for (item in gone) {
            erase(file(item))
            erase(thumbFile(item))
            erase(oldFile(item))
        }
    }
}
