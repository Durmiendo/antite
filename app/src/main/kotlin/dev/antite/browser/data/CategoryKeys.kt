package dev.antite.browser.data

import java.io.File
import java.io.IOException

// A random data key per category, kept in files/keys/category-<name>.key sealed by the history domain key of the key
// ring. Forgetting a category deletes its key file: what its files hold cannot be read again.
class CategoryKeys(private val dir: File, private val domainKey: () -> ByteArray, private val cipher: () -> Cipher256) {
    private val keys = HashMap<String, ByteArray>()

    private fun file(name: String) = File(dir, "category-$name.key")

    @Synchronized
    fun key(name: String): ByteArray {
        keys[name]?.let { return it }
        val f = file(name)
        val aad = name.toByteArray()
        val k = if (f.exists()) Ciphers.unbox(domainKey(), f.readBytes(), aad) else {
            val fresh = Ciphers.randomBytes(Cipher256.KEY)
            dir.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(Ciphers.box(cipher(), domainKey(), fresh, aad))
            if (!tmp.renameTo(f)) throw IOException("cannot write ${f.name}")
            fresh
        }
        keys[name] = k
        return k
    }

    @Synchronized
    fun forget(name: String) {
        keys.remove(name)?.fill(0)
        file(name).delete()
    }

    @Synchronized
    fun lock() {
        keys.values.forEach { it.fill(0) }
        keys.clear()
    }
}
