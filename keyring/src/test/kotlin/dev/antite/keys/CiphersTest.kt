package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CiphersTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // draft-irtf-cfrg-xchacha-03, 2.2.1.
    @Test
    fun hchacha20MatchesTheDraft() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = hex("000000090000004a0000000031415927")
        assertArrayEquals(hex("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc"), Ciphers.hchacha20(key, nonce))
    }

    // draft-irtf-cfrg-xchacha-03, A.3.1. The XChaCha key is the second half of our 64-byte key.
    @Test
    fun xchachaMatchesTheDraft() {
        val key = ByteArray(32) + ByteArray(32) { (0x80 + it).toByte() }
        val nonce = ByteArray(24) { (0x40 + it).toByte() }
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plain = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
            "sunscreen would be it.").toByteArray()
        val expected = hex(
            "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4aa6440bf3a82f4eda7e39" +
                "ae64c6708c54c216cb96b72e1213b4522f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9" +
                "21f9664c97637da9768812f615c68b13b52e" + "c0875924c1c7987947deafd8780acf49",
        )
        assertArrayEquals(expected, Ciphers.seal(Cipher256.XCHACHA, key, nonce, aad, plain))
        assertArrayEquals(plain, Ciphers.open(Cipher256.XCHACHA, key, nonce, aad, expected))
    }

    @Test
    fun everyCipherRoundTripsAndRejectsTampering() {
        val key = Ciphers.randomBytes(Cipher256.KEY)
        val plain = Ciphers.randomBytes(1000)
        for (c in Cipher256.entries) {
            val box = Ciphers.box(c, key, plain, byteArrayOf(7))
            assertEquals(c, Ciphers.boxCipher(box))
            assertEquals(1 + 24 + plain.size + c.overhead, box.size)
            assertArrayEquals(plain, Ciphers.unbox(key, box, byteArrayOf(7)))
            assertThrows(IOException::class.java) { Ciphers.unbox(key, box, byteArrayOf(8)) }
            val bad = box.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
            assertThrows(IOException::class.java) { Ciphers.unbox(key, bad, byteArrayOf(7)) }
            val other = key.copyOf().also { it[if (c == Cipher256.AES) 0 else 40] = (it[0] + 1).toByte() }
            assertThrows(IOException::class.java) { Ciphers.unbox(other, box, byteArrayOf(7)) }
        }
    }

    // The cascade must need both halves: each layer alone is not enough.
    @Test
    fun theCascadeNeedsBothKeys() {
        val key = Ciphers.randomBytes(Cipher256.KEY)
        val box = Ciphers.box(Cipher256.CASCADE, key, "secret".toByteArray())
        for (half in listOf(0, 40)) {
            val other = key.copyOf().also { it[half] = (it[half] + 1).toByte() }
            assertThrows(IOException::class.java) { Ciphers.unbox(other, box) }
        }
    }
}
