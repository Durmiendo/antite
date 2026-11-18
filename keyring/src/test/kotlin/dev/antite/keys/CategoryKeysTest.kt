package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CategoryKeysTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val domain = CoreKey.random()
    private val keyDir get() = File(tmp.root, "keys")
    private fun keys() = CategoryKeys(RealDisk(tmp.root), keyDir, { op -> op(domain) }) { Cipher256.AES }

    @Test
    fun categoryKeysAreRandomAndSealed() {
        val k = keys()
        val a = k.key("a").print()
        assertFalse(a.contentEquals(k.key("b").print()))
        assertArrayEquals(a, keys().key("a").print())
        assertThrows(IOException::class.java) {
            CoreKey.random().use { other -> CategoryKeys(RealDisk(tmp.root), keyDir, { op -> op(other) }) { Cipher256.AES }.key("a") }
        }
    }

    @Test
    fun aForgottenKeyIsGoneForGood() {
        val k = keys()
        val held = k.key("a")
        val a = held.print()
        k.forget("a")
        assertTrue(held.closed)
        assertFalse(File(keyDir, "category-a.key").exists())
        assertFalse(a.contentEquals(keys().key("a").print()))
    }
}
