package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CoreKeyTest {
    private val nonce = ByteArray(24) { it.toByte() }
    private val aad = "aad".toByteArray()

    // A key in the table seals exactly as the same bytes given as an array: one cipher, two ways to hand it the key.
    @Test
    fun aKeyInTheTableSealsAsItsBytes() {
        val raw = Ciphers.randomBytes(64)
        val copy = raw.copyOf()
        CoreKey.import(raw).use { k ->
            assertTrue("import wipes the array", raw.all { it == 0.toByte() })
            for (c in Cipher256.entries) {
                val plain = "plain ${c.name}".toByteArray()
                val sealed = Ciphers.seal(c, k, nonce, aad, plain)
                assertArrayEquals(c.name, sealRaw(c, copy, nonce, aad, plain), sealed)
                assertArrayEquals(c.name, plain, Ciphers.open(c, k, nonce, aad, sealed))
            }
        }
    }

    @Test
    fun boxesOpenEitherWay() {
        val raw = Ciphers.randomBytes(64)
        CoreKey.import(raw.copyOf()).use { k ->
            val box = Ciphers.box(Cipher256.XCHACHA, k, "x".toByteArray(), aad)
            assertArrayEquals("x".toByteArray(), unboxRaw(raw, box, aad))
        }
    }

    @Test
    fun aClosedKeyIsRefused() {
        val k = CoreKey.random()
        val h = k.handle
        k.close()
        k.close()
        assertThrows(IllegalStateException::class.java) { Ciphers.seal(Cipher256.XCHACHA, k, nonce, aad, ByteArray(1)) }
        // The core itself refuses the old number, and the slot reused by a new key does not answer to it.
        val next = CoreKey.random()
        assertThrows(IllegalArgumentException::class.java) { KeyCore.sealWith(2, h, nonce, aad, ByteArray(1), 0, 1, false) }
        assertThrows(IllegalArgumentException::class.java) { KeyCore.keyFree(h) }
        next.close()
    }

    @Test
    fun wrappedKeysComeBackTheSame() {
        for (c in Cipher256.entries) CoreKey.random().use { w ->
            CoreKey.random().use { k ->
                val box = k.wrapUnder(w, c, aad)
                assertEquals(c.id, box[0].toInt())
                val back = CoreKey.unwrap(w, box, aad)!!
                back.use {
                    val sealed = Ciphers.seal(Cipher256.XCHACHA, k, nonce, aad, "same".toByteArray())
                    assertArrayEquals("same".toByteArray(), Ciphers.open(Cipher256.XCHACHA, back, nonce, aad, sealed))
                }
                CoreKey.random().use { other -> assertNull(c.name, CoreKey.unwrap(other, box, aad)) }
                assertNull(c.name, CoreKey.unwrap(w, box, "other".toByteArray()))
                assertNull(c.name, CoreKey.unwrap(w, box.copyOf(box.size - 1), aad))
                assertNull(c.name, CoreKey.unwrap(w, box.also { it[30] = (it[30].toInt() xor 1).toByte() }, aad))
            }
        }
    }

    // A wrapped key opens with Ciphers.unbox too: the same box format; what it holds is the key.
    @Test
    fun wrapIsAnOrdinaryBox() {
        val raw = Ciphers.randomBytes(64)
        val wRaw = Ciphers.randomBytes(64)
        CoreKey.import(wRaw.copyOf()).use { w ->
            CoreKey.import(raw.copyOf()).use { k -> assertArrayEquals(raw, unboxRaw(wRaw, k.wrapUnder(w, Cipher256.CASCADE, aad), aad)) }
        }
    }

    @Test
    fun derivedKeyIsArgon2id() {
        val pw = "correct horse".toByteArray()
        val salt = "antite-salt-0001".toByteArray()
        val params = KeyRing.KdfParams(1, 1024, 2)
        // echo -n "correct horse" | argon2 antite-salt-0001 -id -t 1 -k 1024 -p 2 -l 64 -r
        val raw = "e16513b0b2a2c3228256559f8714fa0786d330bdf53e3cbd39693b15505ed5a99d9681c20aceebdfd9d1dd3118b1ba76f141bc613880a4025a467c19a6a5c8c2".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        CoreKey.derive(pw, salt, params).use { k ->
            val sealed = Ciphers.seal(Cipher256.XCHACHA, k, nonce, aad, ByteArray(3))
            assertArrayEquals(sealRaw(Cipher256.XCHACHA, raw, nonce, aad, ByteArray(3)), sealed)
        }
        assertThrows(IllegalArgumentException::class.java) { CoreKey.derive(pw, ByteArray(4), params) }
    }

    @Test
    fun theTableGrowsAndEveryKeyGoes() {
        val before = KeyCore.keyCount()
        val keys = List(3000) { CoreKey.random() }
        assertEquals(before + 3000, KeyCore.keyCount())
        keys.forEach { it.close() }
        assertEquals(before, KeyCore.keyCount())
    }

    @Test
    fun manyThreadsMakeUseAndFreeKeys() {
        val before = KeyCore.keyCount()
        val errors = AtomicInteger()
        val threads = List(32) { t ->
            Thread {
                repeat(300) {
                    CoreKey.random().use { k ->
                        val n = ByteArray(24).also { it[0] = t.toByte() }
                        val s = Ciphers.seal(Cipher256.XCHACHA, k, n, aad, ByteArray(it))
                        if (!ByteArray(it).contentEquals(Ciphers.open(Cipher256.XCHACHA, k, n, aad, s))) errors.incrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(0, errors.get())
        assertEquals(before, KeyCore.keyCount())
    }

    @Test
    fun wrongImportIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { CoreKey.import(ByteArray(32)) }
    }
}
