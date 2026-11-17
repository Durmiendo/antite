package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

// Files written by this code once and kept: every implementation of the formats (this one after any change, a desktop
// one later) must read them into exactly these contents, and where writing is deterministic write exactly these bytes.
class GoldenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun golden(name: String) = javaClass.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }

    private val key = ByteArray(64) { it.toByte() }
    private val nonce = ByteArray(24) { (100 + it).toByte() }
    private val aad = "antite-golden".toByteArray()
    private val plain = "Antite golden 1".toByteArray()

    @Test
    fun sealIsByteForByte() {
        for (c in Cipher256.entries) {
            val g = golden("seal-${c.name.lowercase()}.bin")
            assertArrayEquals(c.name, g, Ciphers.seal(c, key, nonce, aad, plain))
            assertArrayEquals(c.name, plain, Ciphers.open(c, key, nonce, aad, g))
        }
    }

    @Test
    fun oldBoxesOpen() {
        for (c in Cipher256.entries) {
            val g = golden("box-${c.name.lowercase()}.bin")
            assertEquals(c.id, g[0].toInt())
            assertArrayEquals(c.name, plain, Ciphers.unbox(key, g, aad))
        }
    }

    @Test
    fun vaultFilesAreByteForByte() {
        val content = ByteArray(VaultFile.CHUNK + 100) { (it * 7 % 251).toByte() }
        for (c in Cipher256.entries) {
            val name = "vaultfile-${c.name.lowercase()}.bin"
            val out = ByteArrayOutputStream()
            VaultFile.writer(out, c, key).use { it.write(content) }
            assertArrayEquals(c.name, golden(name), out.toByteArray())
            val f = File(tmp.root, name).apply { writeBytes(golden(name)) }
            assertArrayEquals(c.name, content, VaultFile.Reader(f, key).stream().use { it.readBytes() })
        }
    }

    @Test
    fun anOldLogReads() {
        val f = File(tmp.root, "h.log").apply { writeBytes(golden("log-xchacha.bin")) }
        assertEquals(listOf("r0", "r1", "r2"), EncryptedLog(f, key) { Cipher256.XCHACHA }.read().map { String(it) })
    }

    // The key derivation here is a stand-in for Argon2id, so the file checks the container; Argon2id itself is checked
    // against RFC 9106 where it is implemented.
    @Test
    fun anOldBackupReads() {
        val kdf = KeyRing.Kdf { p, s, params -> MessageDigest.getInstance("SHA-512").digest(p + s + params.toString().toByteArray()) }
        val back = BackupCipher.decrypt(ByteArrayInputStream(golden("backup-v2.bin")), "golden".toCharArray(), kdf).use { it.readBytes() }
        assertArrayEquals(ByteArray(1000) { (it % 13).toByte() }, back)
    }
}
