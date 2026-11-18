package dev.antite.keys

import java.io.File

// A random data key per category, kept in files/keys/category-<name>.key boxed under the history domain key of the key
// ring, and opened in the core. Forgetting a category deletes its key file: what its files hold cannot be read again.
// withDomainKey runs its block with the history domain key held, so locking the ring cannot close it half way.
class CategoryKeys(
    private val disk: Disk,
    private val dir: File,
    private val withDomainKey: ((CoreKey) -> Unit) -> Unit,
    private val cipher: () -> Cipher256,
) {
    private val keys = HashMap<String, CoreKey>()

    private fun file(name: String) = File(dir, "category-$name.key")

    // The category's key, held here: the caller must not close it, lock() and forget() do.
    @Synchronized
    fun key(name: String): CoreKey {
        keys[name]?.let { return it }
        val f = file(name)
        val aad = name.toByteArray()
        val k = if (disk.exists(f)) {
            var opened: CoreKey? = null
            withDomainKey { d -> opened = CoreKey.unwrap(d, disk.readAll(f), aad) }
            opened ?: throw java.io.IOException("category key $name damaged")
        } else {
            val fresh = CoreKey.random()
            disk.mkdirs(dir)
            try {
                var box = ByteArray(0)
                withDomainKey { d -> box = fresh.wrapUnder(d, cipher(), aad) }
                disk.replace(f, box)
            } catch (e: Throwable) {
                fresh.close()
                throw e
            }
            fresh
        }
        keys[name] = k
        return k
    }

    // Every category key file sealed with another cipher than the current one, sealed again: the same keys, each file
    // replaced whole.
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

    // Whether any category key is sealed with another cipher than the current one.
    @Synchronized
    fun needsRewrap(): Boolean {
        val target = cipher()
        return disk.exists(dir) && disk.list(dir).any { it.name.startsWith("category-") && it.name.endsWith(".key") && Ciphers.boxCipher(disk.readAll(it)) != target }
    }

    @Synchronized
    fun forget(name: String) {
        keys.remove(name)?.close()
        disk.deleteDurably(file(name))
    }

    @Synchronized
    fun lock() {
        keys.values.forEach { it.close() }
        keys.clear()
    }
}
