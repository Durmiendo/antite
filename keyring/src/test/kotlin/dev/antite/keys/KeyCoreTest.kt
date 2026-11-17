package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyCoreTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun golden(name: String) = javaClass.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }

    // From the reference implementation (the PHC argon2 tool): echo -n "correct horse" | argon2 antite-salt-0001 -id
    // -t T -k M -p P -l L -r. The first one is the key ring's default.
    @Test
    fun argon2idMatchesTheReference() {
        val cases = listOf(
            listOf(3, 65536, 2, 64) to "1a7f6cec4175818a0ec19bfa8d6d5608f49e59e41a13695c3d02b57e4a3e44eb0718a36da8798088545b4198d1f7c49fd5e5092467b837c80931bb335209070f",
            listOf(1, 8192, 1, 32) to "5bce74d955413cba7a9f92cdfe4b262a4d9e3afdaf6b12fb07a8bb845e51f6c7",
            listOf(2, 1024, 4, 64) to "f4a7ee709edec46d243db8d3eabc25cc40e551199739fec82792a59be9915e82d4f41b53492471349c5d7fdd8fa7c29299f30bf6b196dda827a4e20cc8a53a63",
        )
        for ((p, expected) in cases) {
            val got = KeyCore.argon2id("correct horse".toByteArray(), "antite-salt-0001".toByteArray(), p[0], p[1], p[2], p[3])
            assertArrayEquals(p.toString(), hex(expected), got)
        }
    }

    // The software AES of processors without AES instructions writes and reads the same bytes as the hardware one.
    @Test
    fun softwareAesIsByteForByte() {
        val key = ByteArray(64) { it.toByte() }
        val nonce = ByteArray(24) { (100 + it).toByte() }
        val aad = "antite-golden".toByteArray()
        val plain = "Antite golden 1".toByteArray()
        for (c in listOf(Cipher256.AES, Cipher256.CASCADE)) {
            val g = golden("seal-${c.name.lowercase()}.bin")
            assertArrayEquals(c.name, g, KeyCore.seal(c.id, key, nonce, aad, plain, 0, plain.size, true))
            assertArrayEquals(c.name, plain, KeyCore.open(c.id, key, nonce, aad, g, 0, g.size, true))
            val bad = g.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertNull(c.name, KeyCore.open(c.id, key, nonce, aad, bad, 0, bad.size, true))
        }
    }

    // NIST GCM test case 16 (AES-256, 60-byte text, 20-byte AAD), through the AES half of a 64-byte key.
    @Test
    fun aesGcmMatchesNist() {
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308") + ByteArray(32)
        val nonce = hex("cafebabefacedbaddecaf888") + ByteArray(12)
        val plain = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val expected = hex("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
            "76fc6ece0f4e1768cddf8853bb2d551b")
        for (soft in listOf(false, true)) {
            assertArrayEquals("soft=$soft", expected, KeyCore.seal(Cipher256.AES.id, key, nonce, aad, plain, 0, plain.size, soft))
        }
    }

    @Test
    fun wrongArgumentsGiveNothing() {
        assertNull(KeyCore.seal(Cipher256.XCHACHA.id, ByteArray(32), ByteArray(24), ByteArray(0), ByteArray(4), 0, 4, false))
        assertNull(KeyCore.seal(Cipher256.XCHACHA.id, ByteArray(64), ByteArray(12), ByteArray(0), ByteArray(4), 0, 4, false))
        assertNull(KeyCore.seal(Cipher256.XCHACHA.id, ByteArray(64), ByteArray(24), ByteArray(0), ByteArray(4), 2, 4, false))
        assertNull(KeyCore.seal(9, ByteArray(64), ByteArray(24), ByteArray(0), ByteArray(4), 0, 4, false))
    }
}
