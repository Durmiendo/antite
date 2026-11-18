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
import java.nio.ByteBuffer
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultFileTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val disk by lazy { RealDisk(tmp.root) }

    private val key = Ciphers.randomBytes(Cipher256.KEY)

    private fun data(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    private fun write(plain: ByteArray, cipher: Cipher256 = Cipher256.AES, name: String = "f.enc"): File {
        val f = File(tmp.root, name)
        VaultFile.writer(f.outputStream(), cipher, keyOf(key)).use { out ->
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

    private fun read(f: File, k: CoreKey = keyOf(key)) = VaultFile.Reader(disk, f, k).stream().use { it.readBytes() }

    @Test
    fun contentComesBackForSizesAroundTheChunkInEveryCipher() {
        for (cipher in Cipher256.entries) {
            for (size in listOf(0, 1, VaultFile.CHUNK - 1, VaultFile.CHUNK, VaultFile.CHUNK + 1, 3 * VaultFile.CHUNK, 200_000)) {
                val plain = data(size)
                val f = write(plain, cipher)
                assertEquals(cipher, VaultFile.head(disk, f).cipher)
                assertEquals(size.toLong(), VaultFile.plainLength(f.length(), cipher))
                assertArrayEquals("$cipher size $size", plain, read(f))
            }
        }
    }

    @Test
    fun contentIsNotInTheFile() {
        for (cipher in Cipher256.entries) {
            val stored = write(ByteArray(5000) { 'A'.code.toByte() }, cipher).readBytes()
            assertFalse(String(stored, Charsets.ISO_8859_1).contains("AAAAAAAA"))
        }
    }

    @Test
    fun readsFromAnyPosition() {
        val plain = data(200_000)
        for (cipher in Cipher256.entries) {
            VaultFile.Reader(disk, write(plain, cipher), keyOf(key)).use { r ->
                assertEquals(200_000L, r.length)
                for (pos in listOf(0, 1, VaultFile.CHUNK - 3, VaultFile.CHUNK, 150_000, 199_999)) {
                    val buf = ByteArray(100)
                    var got = 0
                    while (got < 100) {
                        val n = r.read(pos.toLong() + got, buf, got, 100 - got)
                        if (n < 0) break
                        got += n
                    }
                    assertArrayEquals("$cipher from $pos", plain.copyOfRange(pos, minOf(pos + 100, plain.size)), buf.copyOf(got))
                }
                assertEquals(-1, r.read(200_000, ByteArray(1), 0, 1))
            }
        }
    }

    @Test
    fun changedByteIsRefused() {
        for (cipher in Cipher256.entries) {
            val f = write(data(100_000), cipher)
            val bytes = f.readBytes()
            bytes[VaultFile.HEADER + 70_000] = (bytes[VaultFile.HEADER + 70_000].toInt() xor 1).toByte()
            f.writeBytes(bytes)
            assertThrows(IOException::class.java) { read(f) }
        }
    }

    @Test
    fun anotherCipherInTheHeaderIsRefused() {
        val f = write(data(1000), Cipher256.XCHACHA)
        val bytes = f.readBytes().also { it[5] = Cipher256.AES.id.toByte() }
        f.writeBytes(bytes)
        assertThrows(IOException::class.java) { read(f) }
    }

    @Test
    fun fileCutAtAChunkBorderIsRefused() {
        for (cipher in Cipher256.entries) {
            val f = write(data(3 * VaultFile.CHUNK), cipher)
            f.writeBytes(f.readBytes().copyOf(VaultFile.HEADER + 2 * (VaultFile.CHUNK + cipher.overhead)))
            assertThrows(IOException::class.java) { read(f) }
        }
    }

    @Test
    fun fileCutInsideAChunkIsRefused() {
        val f = write(data(100_000))
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 5))
        assertThrows(IOException::class.java) { read(f) }
    }

    @Test
    fun swappedChunksAreRefused() {
        for (cipher in Cipher256.entries) {
            val f = write(data(3 * VaultFile.CHUNK), cipher)
            val bytes = f.readBytes()
            val size = VaultFile.CHUNK + cipher.overhead
            val first = bytes.copyOfRange(VaultFile.HEADER, VaultFile.HEADER + size)
            System.arraycopy(bytes, VaultFile.HEADER + size, bytes, VaultFile.HEADER, size)
            System.arraycopy(first, 0, bytes, VaultFile.HEADER + size, size)
            f.writeBytes(bytes)
            assertThrows(IOException::class.java) { read(f) }
        }
    }

    @Test
    fun anotherKeyDoesNotOpen() {
        val f = write(data(1000))
        assertThrows(IOException::class.java) { read(f, CoreKey.random()) }
    }

    @Test
    fun plainFileIsNotTakenForAVaultFile() {
        val f = File(tmp.root, "plain").apply { writeBytes(data(5000)) }
        assertThrows(IOException::class.java) { read(f) }
    }

    @Test
    fun theReaderClosesItsKey() {
        val f = write(data(10))
        val mine = keyOf(key)
        VaultFile.Reader(disk, f, mine).close()
        assertTrue(mine.closed)
    }

    // A version 1 file as the previous release wrote it: the key wrapped in the header, AES chunks.
    private fun v1File(plain: ByteArray, key32: ByteArray, mask: Int): File {
        val blob = ByteArray(32) { (key32[it].toInt() xor mask).toByte() }
        val out = ByteBuffer.allocate(VaultFile.HEADER).put("AVLT".toByteArray()).put(1).put(1).put(blob.size.toByte()).put(blob).array()
        val chunks = maxOf(1, (plain.size + VaultFile.CHUNK - 1) / VaultFile.CHUNK)
        val body = (0 until chunks).map { i ->
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).putLong(i.toLong()).array()))
            c.updateAAD(ByteBuffer.allocate(14).put("AVLT".toByteArray()).put(1).putLong(i.toLong()).put(if (i == chunks - 1) 1 else 0).array())
            c.doFinal(plain, i * VaultFile.CHUNK, minOf(VaultFile.CHUNK, plain.size - i * VaultFile.CHUNK))
        }
        return File(tmp.root, "v1.enc").apply { writeBytes(body.fold(out) { a, b -> a + b }) }
    }

    @Test
    fun aVersion1FileMovesOverByAHeaderRewrite() {
        val plain = data(150_000)
        val key32 = Ciphers.randomBytes(32)
        val f = v1File(plain, key32, 0x5A)
        val head = VaultFile.head(disk, f)
        assertEquals(1, head.version)
        val k = { VaultFile.v1Key(head) { b -> ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() } } }
        assertArrayEquals(plain, read(f, k()))
        VaultFile.upgradeHeader(disk, f)
        assertEquals(2, VaultFile.head(disk, f).version)
        assertArrayEquals(plain, read(f, k()))
    }

    @Test
    fun wipeZeroesAndDeletes() {
        val f = write(data(200_000))
        val same = File(f.path)
        val raf = java.io.RandomAccessFile(same, "r")
        VaultFile.wipe(disk, f)
        assertFalse(f.exists())
        // The data the open handle still sees was overwritten in place.
        val rest = ByteArray(1000).also { raf.seek(VaultFile.HEADER.toLong()); raf.readFully(it) }
        raf.close()
        assertTrue(rest.all { it == 0.toByte() })
    }
}
