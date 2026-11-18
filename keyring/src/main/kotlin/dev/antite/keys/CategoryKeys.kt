package dev.antite.keys

import java.io.File

// A random data key per category, kept in files/keys/category-<name>.key sealed by the history domain key of the key
// ring. Forgetting a category deletes its key file: what its files hold cannot be read again.
// withDomainKey runs its block with the history domain key held, so locking the ring cannot wipe it half way.
class CategoryKeys(
    private val disk: Disk,
    private val dir: File,
    private val withDomainKey: ((ByteArray) -> ByteArray) -> ByteArray,
    private val cipher: () -> Cipher256,
) {
    private val keys = HashMap<String, ByteArray>()

    private fun file(name: String) = File(dir, "category-$name.key")

    @Synchronized
    fun key(name: String): ByteArray {
        keys[name]?.let { return it }
        val f = file(name)
        val aad = name.toByteArray()
        val k = if (disk.exists(f)) withDomainKey { d -> Ciphers.unbox(d, disk.readAll(f), aad) } else {
            val fresh = Ciphers.randomBytes(Cipher256.KEY)
            disk.mkdirs(dir)
            disk.replace(f, withDomainKey { d -> Ciphers.box(cipher(), d, fresh, aad) })
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
            disk.replace(f, withDomainKey { d ->
                val plain = Ciphers.unbox(d, boxed, aad)
                try {
                    Ciphers.box(target, d, plain, aad)
                } finally {
                    plain.fill(0)
                }
            })
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
        keys.remove(name)?.fill(0)
        disk.deleteDurably(file(name))
    }

    @Synchronized
    fun lock() {
        keys.values.forEach { it.fill(0) }
        keys.clear()
    }
}
