package dev.antite.browser.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class BackupCipherTest {
    @Before
    fun fastKdf() {
        BackupCipher.iterations = 1000
    }

    private fun seal(plain: ByteArray, password: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCipher.encrypt(out, password.toCharArray()).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, password: String): ByteArray =
        BackupCipher.decrypt(ByteArrayInputStream(sealed), password.toCharArray()).use { it.readBytes() }

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
        val header = 37
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
}
