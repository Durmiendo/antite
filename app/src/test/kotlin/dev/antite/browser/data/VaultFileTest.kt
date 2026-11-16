package dev.antite.browser.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Random

class VaultFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // A stand-in for the device key: enough to tell this device's wrapping from another's.
    private fun keys(mask: Int) = VaultFile.Keys(
        wrap = { k -> ByteArray(k.size) { (k[it].toInt() xor mask).toByte() } },
        unwrap = { k -> ByteArray(k.size) { (k[it].toInt() xor mask).toByte() } },
    )

    private val device = keys(0x5A)

    private fun data(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    private fun write(plain: ByteArray, name: String = "f.enc"): File {
        val f = File(tmp.root, name)
        VaultFile.writer(f.outputStream(), device).use { out ->
            // In uneven pieces, as a download delivers them.
            var at = 0
            var step = 1
            while (at < plain.size) {
                val n = minOf(step, plain.size - at)
                out.write(plain, at, n)
                at += n
                step = step * 3 + 7
            }
        }
        return f
    }

    private fun read(f: File, keys: VaultFile.Keys = device) = VaultFile.Reader(f, keys).stream().use { it.readBytes() }

    @Test
    fun contentComesBackForSizesAroundTheChunk() {
        for (size in listOf(0, 1, VaultFile.CHUNK - 1, VaultFile.CHUNK, VaultFile.CHUNK + 1, 3 * VaultFile.CHUNK, 200_000)) {
            val plain = data(size)
            val f = write(plain)
            assertEquals(size.toLong(), VaultFile.plainLength(f.length()))
            assertArrayEquals("size $size", plain, read(f))
        }
    }

    @Test
    fun contentIsNotInTheFile() {
        val plain = ByteArray(5000) { 'A'.code.toByte() }
        val stored = write(plain).readBytes()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("AAAAAAAA"))
    }

    @Test
    fun readsFromAnyPosition() {
        val plain = data(200_000)
        VaultFile.Reader(write(plain), device).use { r ->
            assertEquals(200_000L, r.length)
            for (pos in listOf(0, 1, VaultFile.CHUNK - 3, VaultFile.CHUNK, 150_000, 199_999)) {
                val buf = ByteArray(100)
                var got = 0
                while (got < 100) {
                    val n = r.read(pos.toLong() + got, buf, got, 100 - got)
                    if (n < 0) break
                    got += n
                }
                assertArrayEquals("from $pos", plain.copyOfRange(pos, minOf(pos + 100, plain.size)), buf.copyOf(got))
            }
            assertEquals(-1, r.read(200_000, ByteArray(1), 0, 1))
        }
    }

    @Test
    fun changedByteIsRefused() {
        val f = write(data(100_000))
        val bytes = f.readBytes()
        bytes[VaultFile.HEADER + 70_000] = (bytes[VaultFile.HEADER + 70_000].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        assertThrows(IOException::class.java) { read(f) }
    }

    @Test
    fun fileCutAtAChunkBorderIsRefused() {
        val f = write(data(3 * VaultFile.CHUNK))
        f.writeBytes(f.readBytes().copyOf(VaultFile.HEADER + 2 * (VaultFile.CHUNK + VaultFile.TAG)))
        assertThrows(IOException::class.java) { read(f) }
    }

    @Test
    fun fileCutInsideAChunkIsRefused() {
        val f = write(data(100_000))
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 5))
        assertThrows(IOException::class.java) { read(f) }
    }

    @Test
    fun swappedChunksAreRefused() {
        val f = write(data(3 * VaultFile.CHUNK))
        val bytes = f.readBytes()
        val size = VaultFile.CHUNK + VaultFile.TAG
        val first = bytes.copyOfRange(VaultFile.HEADER, VaultFile.HEADER + size)
        System.arraycopy(bytes, VaultFile.HEADER + size, bytes, VaultFile.HEADER, size)
        System.arraycopy(first, 0, bytes, VaultFile.HEADER + size, size)
        f.writeBytes(bytes)
        assertThrows(IOException::class.java) { read(f) }
    }

    @Test
    fun backupCopyOpensOnAnotherDeviceAfterAdoption() {
        val plain = data(150_000)
        val other = keys(0x33)
        val moved = File(tmp.root, "moved.enc")
        VaultFile.forBackup(write(plain), device).use { input -> moved.outputStream().use { input.copyTo(it) } }
        // Not usable as it is: the open key is only for the way inside the backup.
        assertThrows(IOException::class.java) { read(moved, other) }
        VaultFile.adopt(moved, other)
        assertArrayEquals(plain, read(moved, other))
        assertThrows(IOException::class.java) { VaultFile.adopt(moved, other) }
    }

    @Test
    fun anotherDevicesFileDoesNotOpen() {
        val f = write(data(1000))
        assertThrows(IOException::class.java) { read(f, keys(0x33)) }
    }

    @Test
    fun plainFileIsNotTakenForAVaultFile() {
        val f = File(tmp.root, "plain").apply { writeBytes(data(5000)) }
        assertThrows(IOException::class.java) { read(f) }
    }
}
