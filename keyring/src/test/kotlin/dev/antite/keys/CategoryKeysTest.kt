package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    // A stand-in for the Keystore: a key per alias that can be destroyed for good.
    private class FakeDevice : KeyRing.DeviceKeys {
        val keys = HashMap<String, CoreKey>()
        override fun wrap(alias: String, plain: ByteArray) = Ciphers.box(Cipher256.AES, keys.getOrPut(alias) { CoreKey.random() }, plain)
        override fun unwrap(alias: String, blob: ByteArray) = Ciphers.unbox(keys[alias] ?: throw IOException("no key $alias"), blob)
        override fun destroy(alias: String) {
            keys.remove(alias)?.close()
        }
    }

    private val device = FakeDevice()
    private fun locking() = CategoryKeys(RealDisk(tmp.root), keyDir, { op -> op(domain) }, cipher = { Cipher256.XCHACHA }) { name, dir ->
        KeyRing(RealDisk(tmp.root), dir, device, fastKdf(), prefix = "cat-$name", domains = listOf(CategoryKeys.DATA), first = listOf(CategoryKeys.DATA))
    }
    private fun pw(s: String) = s.toByteArray()
    private fun keys() = CategoryKeys(RealDisk(tmp.root), keyDir, { op -> op(domain) }, cipher = { Cipher256.AES })

    @Test
    fun categoryKeysAreRandomAndSealed() {
        val k = keys()
        val a = k.key("a").print()
        assertFalse(a.contentEquals(k.key("b").print()))
        assertArrayEquals(a, keys().key("a").print())
        assertThrows(IOException::class.java) {
            CoreKey.random().use { other -> CategoryKeys(RealDisk(tmp.root), keyDir, { op -> op(other) }, cipher = { Cipher256.AES }).key("a") }
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

    @Test
    fun aPasswordLockKeepsTheKeyAndGivesItOnlyForThePassword() {
        val k = locking()
        val a = k.key("a").print()
        val given = pw("secret")
        k.setLock("a", KeyMode.PASSWORD, given)
        assertTrue(given.all { it == 0.toByte() })
        assertFalse(File(keyDir, "category-a.key").exists())
        assertTrue(device.keys.isEmpty())
        val again = locking()
        assertEquals(KeyMode.PASSWORD, again.lockOf("a"))
        assertThrows(KeyRing.LockedException::class.java) { again.key("a") }
        assertThrows(KeyRing.WrongPasswordException::class.java) { again.open("a", pw("wrong")) }
        assertThrows(KeyRing.LockedException::class.java) { again.key("a") }
        again.open("a", pw("secret"))
        assertArrayEquals(a, again.key("a").print())
        again.closeLocked()
        assertThrows(KeyRing.LockedException::class.java) { again.key("a") }
    }

    @Test
    fun aDeviceLockGoesWithItsDeviceKey() {
        val k = locking()
        val a = k.key("a").print()
        k.setLock("a", KeyMode.DEVICE, null)
        val again = locking()
        assertEquals(listOf("a"), again.locked())
        again.open("a", null)
        assertArrayEquals(a, again.key("a").print())
        device.keys.keys.toList().forEach(device::destroy)
        assertThrows(IOException::class.java) { locking().open("a", null) }
    }

    @Test
    fun bothNeedThePasswordAndTheDevice() {
        val k = locking()
        val a = k.key("a").print()
        k.setLock("a", KeyMode.BOTH, pw("pw"))
        assertEquals(1, device.keys.size)
        assertThrows(KeyRing.WrongPasswordException::class.java) { locking().open("a", null) }
        val again = locking().apply { open("a", pw("pw")) }
        assertArrayEquals(a, again.key("a").print())
    }

    @Test
    fun changingAndTakingOffTheLockKeepsTheKey() {
        val k = locking()
        val a = k.key("a").print()
        k.setLock("a", KeyMode.DEVICE, null)
        k.setLock("a", KeyMode.PASSWORD, pw("pw"))
        assertTrue("the device key of the old lock is destroyed", device.keys.isEmpty())
        assertEquals(KeyMode.PASSWORD, locking().lockOf("a"))
        assertThrows(KeyRing.LockedException::class.java) { locking().setLock("a", null, null) }
        k.setLock("a", null, null)
        assertFalse(File(keyDir, "locked-a").exists())
        assertEquals(null, locking().lockOf("a"))
        assertArrayEquals(a, locking().key("a").print())
    }

    @Test
    fun forgettingALockedCategoryDestroysItsRing() {
        val k = locking()
        val a = k.key("a").print()
        k.setLock("a", KeyMode.DEVICE, null)
        locking().forget("a")
        assertTrue(device.keys.isEmpty())
        assertFalse(File(keyDir, "locked-a").exists())
        assertFalse(a.contentEquals(locking().key("a").print()))
    }
}
