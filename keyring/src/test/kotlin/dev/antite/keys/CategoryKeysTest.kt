package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CategoryKeysTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val domain = Ciphers.randomBytes(Cipher256.KEY)
    private val keyDir get() = File(tmp.root, "keys")
    private fun keys() = CategoryKeys(RealDisk(tmp.root), keyDir, { op -> op(domain) }) { Cipher256.AES }

    @Test
    fun categoryKeysAreRandomAndSealed() {
        val k = keys()
        val a = k.key("a").copyOf()
        assertFalse(a.contentEquals(k.key("b")))
        assertArrayEquals(a, keys().key("a"))
        assertThrows(IOException::class.java) {
            CategoryKeys(RealDisk(tmp.root), keyDir, { op -> op(Ciphers.randomBytes(Cipher256.KEY)) }) { Cipher256.AES }.key("a")
        }
    }

    @Test
    fun aForgottenKeyIsGoneForGood() {
        val k = keys()
        val a = k.key("a").copyOf()
        k.forget("a")
        assertFalse(File(keyDir, "category-a.key").exists())
        assertFalse(a.contentEquals(keys().key("a")))
    }
}
