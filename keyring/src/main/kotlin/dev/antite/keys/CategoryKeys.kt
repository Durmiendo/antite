package dev.antite.keys

import java.io.File

// A random data key per category (decision 0011). An open category's key is boxed under the history domain key of the
// app's key ring, in category-<name>.key. A locked category's key sits in a ring of its own, locked-<name>/, under what
// the user chose: the device unlock, a password of the category, or both; it is given out only after open().
// A lock put on, changed or taken off keeps the data key and writes its new place whole before the old one goes, so a
// stop leaves one or the other: a key file next to a ring means the switch was cut short, and the ring goes.
// Forgetting a category destroys both: what its files hold cannot be read again.
// withDomainKey runs its block with the history domain key held, so locking the app's ring cannot close it half way.
class CategoryKeys(
    private val disk: Disk,
    private val dir: File,
    private val withDomainKey: ((CoreKey) -> Unit) -> Unit,
    private val cipher: () -> Cipher256,
    private val ringOf: (name: String, dir: File) -> KeyRing = { _, _ -> throw IllegalStateException("no category locks") },
) {
    companion object {
        val DATA = KeyDomain("data")
    }

    // Keys of open categories, held here.
    private val keys = HashMap<String, CoreKey>()
    private val rings = HashMap<String, KeyRing>()
    // Keys of locked categories opened since the last lock; their rings hold them.
    private val opened = HashMap<String, CoreKey>()

    private fun file(name: String) = File(dir, "category-$name.key")
    private fun ringDir(name: String) = File(dir, "locked-$name")

    private fun ring(name: String): KeyRing? {
        rings[name]?.let { return it }
        if (!disk.exists(ringDir(name))) return null
        val r = ringOf(name, ringDir(name))
        if (disk.exists(file(name)) || !r.exists) {
            r.destroy()
            return null
        }
        return r.also { rings[name] = it }
    }

    // What locks the category's key; null for an open category.
    @Synchronized
    fun lockOf(name: String): KeyMode? = ring(name)?.mode

    // Categories whose key is locked, by the folders of their rings.
    @Synchronized
    fun locked(): List<String> = if (!disk.exists(dir)) emptyList()
    else disk.children(dir).filter { disk.isDirectory(it) }.map { it.name }.filter { it.startsWith("locked-") }.map { it.removePrefix("locked-") }.filter { ring(it) != null }

    // The category's key, held here or by its ring: the caller must not close it; lock() and forget() do. A locked
    // category's key only after open().
    @Synchronized
    fun key(name: String): CoreKey {
        keys[name]?.let { return it }
        if (ring(name) != null) return opened[name] ?: throw KeyRing.LockedException()
        val k = if (disk.exists(file(name))) boxed(name) else {
            val fresh = CoreKey.random()
            disk.mkdirs(dir)
            try {
                var box = ByteArray(0)
                withDomainKey { d -> box = fresh.wrapUnder(d, cipher(), name.toByteArray()) }
                disk.replace(file(name), box)
            } catch (e: Throwable) {
                fresh.close()
                throw e
            }
            fresh
        }
        keys[name] = k
        return k
    }

    // A new handle on an open category's key, from its file.
    private fun boxed(name: String): CoreKey {
        var opened: CoreKey? = null
        withDomainKey { d -> opened = CoreKey.unwrap(d, disk.readAll(file(name)), name.toByteArray()) }
        return opened ?: throw java.io.IOException("category key $name damaged")
    }

    // A locked category's key opened: with its password when the lock has one (wiped here); the device part needs the
    // device unlock just before, since the device key store lets its key out only for a short time after it.
    @Synchronized
    fun open(name: String, password: ByteArray?) {
        try {
            val r = ring(name) ?: return
            if (r.mode.usesPassword) r.unlock(password?.copyOf() ?: throw KeyRing.WrongPasswordException())
            opened[name] = r.key(DATA)
            if (r.needsRewrap()) r.rewrap()
        } finally {
            password?.fill(0)
        }
    }

    // A lock put on (mode), changed, or taken off (null). The data key stays; a locked category must be open. The
    // device part needs the device unlock just before. A password is wiped here.
    @Synchronized
    fun setLock(name: String, mode: KeyMode?, password: ByteArray?) {
        try {
            val r = ring(name)
            when {
                r == null && mode == null -> return
                r == null -> {
                    key(name)
                    val fresh = ringOf(name, ringDir(name))
                    fresh.create(mode!!, password?.copyOf(), keyOf = { boxed(name) })
                    disk.deleteDurably(file(name))
                    keys.remove(name)?.close()
                    rings[name] = fresh
                    opened[name] = fresh.key(DATA)
                }
                mode == null -> {
                    val k = opened[name] ?: throw KeyRing.LockedException()
                    var box = ByteArray(0)
                    withDomainKey { d -> box = k.wrapUnder(d, cipher(), name.toByteArray()) }
                    disk.replace(file(name), box)
                    opened.remove(name)
                    rings.remove(name)
                    r.destroy()
                }
                else -> {
                    if (opened[name] == null) throw KeyRing.LockedException()
                    r.setMode(mode, password?.copyOf())
                    opened[name] = r.key(DATA)
                }
            }
        } finally {
            password?.fill(0)
        }
    }

    // Every open category's key file sealed with another cipher than the current one, sealed again: the same keys,
    // each file replaced whole. Locked categories are sealed again when they are opened.
    @Synchronized
    fun rewrap() {
        val target = cipher()
        if (!disk.exists(dir)) return
        for (f in disk.list(dir)) {
            val name = f.name.removePrefix("category-").removeSuffix(".key")
            if (name == f.name || !f.name.endsWith(".key")) continue
            val boxed = disk.readAll(f)
            if (Ciphers.boxCipher(boxed) == target) continue
            val aad = name.toByteArray()
            var box = ByteArray(0)
            withDomainKey { d ->
                box = (CoreKey.unwrap(d, boxed, aad) ?: throw java.io.IOException("category key $name damaged")).use { it.wrapUnder(d, target, aad) }
            }
            disk.replace(f, box)
        }
    }

    // Whether any open category's key is sealed with another cipher than the current one.
    @Synchronized
    fun needsRewrap(): Boolean {
        val target = cipher()
        return disk.exists(dir) && disk.list(dir).any { it.name.startsWith("category-") && it.name.endsWith(".key") && Ciphers.boxCipher(disk.readAll(it)) != target }
    }

    @Synchronized
    fun forget(name: String) {
        keys.remove(name)?.close()
        opened.remove(name)
        (rings.remove(name) ?: ringDir(name).takeIf { disk.exists(it) }?.let { ringOf(name, it) })?.destroy()
        disk.deleteDurably(file(name))
    }

    // The keys of locked categories go: they need open() again. Open categories keep theirs.
    @Synchronized
    fun closeLocked() {
        opened.clear()
        rings.values.forEach { it.lock() }
    }

    @Synchronized
    fun lock() {
        keys.values.forEach { it.close() }
        keys.clear()
        closeLocked()
    }
}
