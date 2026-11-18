package dev.antite.keys

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// The host build of the key core fails its n-th allocation on request (KEYCORE_FAULTS).
object KeyCoreFaults {
    @JvmStatic external fun arm(n: Long)
    @JvmStatic external fun calls(): Long
    @JvmStatic external fun live(): Long
}

// Running out of memory is neither damage nor a wrong key: every allocation of the core fails in turn, and each time
// the caller gets an OutOfMemoryError, nothing is left allocated, and the next call works.
class KeyCoreFaultTest {
    private var baseline = 0L

    @Before
    fun start() {
        KeyCore.aesHardware()
        KeyCoreFaults.arm(-1)
        baseline = KeyCoreFaults.live()
    }

    @After
    fun disarm() = KeyCoreFaults.arm(-1)

    private val key = ByteArray(64) { it.toByte() }
    private val nonce = ByteArray(24) { (7 * it).toByte() }
    private val aad = "aad".toByteArray()
    private val plain = ByteArray(70000) { (it * 31).toByte() }

    // Returns how many allocations the operation makes.
    private fun everyAllocationFails(name: String, op: () -> Unit): Long {
        var n = 1L
        while (true) {
            KeyCoreFaults.arm(n)
            var failed = false
            try {
                op()
            } catch (e: OutOfMemoryError) {
                failed = true
            } catch (e: Throwable) {
                fail("$name, allocation $n: $e")
            }
            val calls = KeyCoreFaults.calls()
            KeyCoreFaults.arm(-1)
            assertEquals("$name, allocation $n: left allocated", baseline, KeyCoreFaults.live())
            if (!failed) {
                assertTrue("$name, allocation $n: did not fail, though it was reached", calls < n)
                break
            }
            op()
            n++
        }
        assertTrue("$name made no allocation to fail", n > 1)
        return n - 1
    }

    @Test
    fun sealAndOpenUnderEveryFailure() {
        for (c in Cipher256.entries) for (soft in listOf(false, true)) {
            val sealed = KeyCore.seal(c.id, key, nonce, aad, plain, 0, plain.size, soft)
            // Additional data, the work buffer, the array handed back; the key takes a slot of the pool.
            assertEquals(3, everyAllocationFails("seal $c soft=$soft") {
                assertArrayEquals(sealed, KeyCore.seal(c.id, key, nonce, aad, plain, 0, plain.size, soft))
            })
            everyAllocationFails("open $c soft=$soft") {
                assertArrayEquals(plain, KeyCore.open(c.id, key, nonce, aad, sealed, 0, sealed.size, soft))
            }
        }
    }

    @Test
    fun argon2UnderEveryFailure() {
        val expected = KeyCore.argon2id("pw".toByteArray(), ByteArray(16), 1, 64, 2, 64)
        val made = everyAllocationFails("argon2id") {
            assertArrayEquals(expected, KeyCore.argon2id("pw".toByteArray(), ByteArray(16), 1, 64, 2, 64))
        }
        // Password, salt, output and the array handed back, plus Argon2's own: its region, blocks and index buffer.
        assertEquals(7, made)
    }

    @Test
    fun keysInTheTableUnderEveryFailure() {
        val raw = Ciphers.randomBytes(64)
        CoreKey.import(raw.copyOf()).use { w ->
            CoreKey.import(raw.copyOf()).use { k ->
                val box = k.wrapUnder(w, Cipher256.XCHACHA)
                val sealed = Ciphers.seal(Cipher256.XCHACHA, k, nonce, aad, plain)
                everyAllocationFails("seal with a handle") { assertArrayEquals(sealed, Ciphers.seal(Cipher256.XCHACHA, k, nonce, aad, plain)) }
                everyAllocationFails("wrap") { CoreKey.unwrap(w, k.wrapUnder(w, Cipher256.CASCADE))!!.close() }
                everyAllocationFails("unwrap") { CoreKey.unwrap(w, box)!!.close() }
                everyAllocationFails("derive") { CoreKey.derive("pw".toByteArray(), ByteArray(16), KeyRing.KdfParams(1, 64, 2)).close() }
            }
        }
    }

    // A failure leaves no key behind in the table either.
    @Test
    fun noKeyLeftAfterFailures() {
        val before = KeyCore.keyCount()
        CoreKey.random().use { w ->
            val box = CoreKey.random().use { it.wrapUnder(w, Cipher256.XCHACHA) }
            for (n in 1L..12L) {
                KeyCoreFaults.arm(n)
                try {
                    CoreKey.unwrap(w, box)?.close()
                    CoreKey.derive("pw".toByteArray(), ByteArray(16), KeyRing.KdfParams(1, 64, 2)).close()
                } catch (e: OutOfMemoryError) {
                } finally {
                    KeyCoreFaults.arm(-1)
                }
            }
        }
        assertEquals(before, KeyCore.keyCount())
    }

    // Random bytes either come or the call throws: never a buffer left as it was.
    @Test
    fun randomNeverLeavesZeros() {
        for (n in 1L..8L) {
            KeyCoreFaults.arm(n)
            val out = ByteArray(64)
            try {
                KeyCore.random(out)
            } catch (e: OutOfMemoryError) {
                continue
            } finally {
                KeyCoreFaults.arm(-1)
            }
            assertFalse("allocation $n failed, random left zeros", out.all { it == 0.toByte() })
        }
    }
}
