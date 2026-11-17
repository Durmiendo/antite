package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random

class BackupCipherTest {
    private val asked = ArrayList<KeyRing.KdfParams>()
    private lateinit var kdf: KeyRing.Kdf

    @Before
    fun fastKdf() {
        kdf = KeyRing.Kdf { password, salt, params ->
            asked += params
            MessageDigest.getInstance("SHA-512").digest(password + salt + params.toString().toByteArray())
        }
        BackupCipher.params = KeyRing.KdfParams(2, 64, 1)
    }

    private fun seal(plain: ByteArray, password: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCipher.encrypt(out, password.toCharArray(), kdf).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, password: String): ByteArray =
        BackupCipher.decrypt(ByteArrayInputStream(sealed), password.toCharArray(), kdf).use { it.readBytes() }

    @Test
    fun roundTripsAcrossChunkBoundaries() {
        for (size in listOf(0, 1, BackupCipher.CHUNK - 1, BackupCipher.CHUNK, BackupCipher.CHUNK + 1, 3 * BackupCipher.CHUNK + 7)) {
            val plain = Random(size).nextBytes(size)
            assertArrayEquals("size $size", plain, open(seal(plain, "пароль"), "пароль"))
        }
    }

    @Test
    fun wrongPasswordIsRejected() {
        val sealed = seal(Random(1).nextBytes(5000), "right")
        assertThrows(WrongPasswordException::class.java) { open(sealed, "wrong") }
    }

    @Test
    fun truncationAtChunkBoundaryIsRejected() {
        val sealed = seal(Random(2).nextBytes(2 * BackupCipher.CHUNK + 10), "p")
        val header = 42
        val firstChunk = 4 + BackupCipher.CHUNK + 16
        assertThrows(WrongPasswordException::class.java) { open(sealed.copyOf(header + firstChunk), "p") }
        assertThrows(WrongPasswordException::class.java) { open(sealed.copyOf(sealed.size - 1), "p") }
    }

    @Test
    fun corruptedByteIsRejected() {
        val sealed = seal(Random(3).nextBytes(10_000), "p")
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 1).toByte()
        assertThrows(WrongPasswordException::class.java) { open(sealed, "p") }
    }

    @Test
    fun trailingGarbageIsRejected() {
        val sealed = seal(Random(4).nextBytes(100), "p")
        assertThrows(WrongPasswordException::class.java) { open(sealed + byteArrayOf(0), "p") }
    }

    @Test
    fun foreignFileIsRejected() {
        assertThrows(IOException::class.java) { open("PK\u0003\u0004 not a backup at all".toByteArray(), "p") }
    }

    @Test
    fun hugeWorkIsRefusedBeforeDerivingTheKey() {
        for ((offset, value) in listOf(9 to 1000, 13 to 4 * 1024 * 1024, 13 to 0)) {
            val sealed = seal(byteArrayOf(1, 2, 3), "p")
            ByteBuffer.wrap(sealed).putInt(offset, value)
            asked.clear()
            val e = assertThrows(IOException::class.java) { open(sealed, "p") }
            assertEquals("bad header", e.message)
            assertTrue(asked.isEmpty())
        }
    }

    @Test
    fun keyComesFromTheParametersInTheFile() {
        val sealed = seal(byteArrayOf(5, 6), "p")
        BackupCipher.params = KeyRing.KdfParams(3, 128, 2)
        asked.clear()
        assertArrayEquals(byteArrayOf(5, 6), open(sealed, "p"))
        assertEquals(listOf(KeyRing.KdfParams(2, 64, 1)), asked)
    }

    @Test
    fun newFilesAreVersion2() {
        assertEquals(2, seal(byteArrayOf(), "p")[8].toInt())
    }

    // Written by build 16 (PBKDF2, 1000 rounds); old backups must still open.
    @Test
    fun version1BackupStillOpens() {
        val v1 = "414e54495445424b01000003e86013061dfd8431ed43a2546b34d52874d07ebf4c91150abc000000200884c4466c44c2dcdeab8dd0fc12e6d0daa0e58cf308f832d9c4b90662f6a1dc".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("version 1 backup", String(open(v1, "old")))
        assertThrows(WrongPasswordException::class.java) { open(v1, "new") }
    }
}
