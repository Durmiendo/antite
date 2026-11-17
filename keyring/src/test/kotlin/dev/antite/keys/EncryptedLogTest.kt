package dev.antite.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class EncryptedLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = Ciphers.randomBytes(Cipher256.KEY)
    private fun file() = File(tmp.root, "h.log")
    private fun log(cipher: Cipher256 = Cipher256.AES, k: ByteArray = key) = EncryptedLog(file(), k) { cipher }
    private fun rec(i: Int) = "record $i".toByteArray()
    private fun texts(l: List<ByteArray>) = l.map { String(it) }

    private fun written(n: Int, cipher: Cipher256 = Cipher256.AES): EncryptedLog =
        log(cipher).also { l -> l.read(); repeat(n) { l.append(rec(it)) } }

    @Test
    fun aNewLogIsEmptyAndKeepsWhatIsAppended() {
        for (c in Cipher256.entries) {
            file().delete()
            written(5, c)
            assertEquals((0 until 5).map { "record $it" }, texts(log(c).read()))
        }
    }

    @Test
    fun appendingAfterReopeningContinuesTheChain() {
        written(2)
        val l = log()
        l.read()
        l.append(rec(2))
        assertEquals(listOf("record 0", "record 1", "record 2"), texts(log().read()))
    }

    @Test
    fun anUnfinishedAppendIsCutOff() {
        written(3)
        val size = file().length()
        RandomAccessFile(file(), "rw").use { it.setLength(size + 7); it.seek(size); it.writeInt(500) }
        val l = log()
        assertEquals(3, l.read().size)
        assertEquals(size, file().length())
        l.append(rec(3))
        assertEquals(4, log().read().size)
    }

    @Test
    fun aRecordTheHeaderDidNotCountYetIsTakenIn() {
        written(2)
        val before = file().readBytes()
        log().also { it.read(); it.append(rec(2)) }
        val after = file().readBytes()
        // The record is on the disk, the header is still the old one: a stop between the two writes.
        System.arraycopy(before, 0, after, 0, 256)
        file().writeBytes(after)
        assertEquals(3, log().read().size)
        assertEquals(3, log().read().size)
    }

    @Test
    fun aCutTailShows() {
        written(3)
        val bytes = file().readBytes()
        val last = lastRecordStart(bytes)
        file().writeBytes(bytes.copyOf(last))
        assertThrows(EncryptedLog.DamagedException::class.java) { log().read() }
    }

    @Test
    fun aDroppedRecordInTheMiddleShows() {
        written(3)
        val bytes = file().readBytes()
        val starts = recordStarts(bytes)
        val cut = bytes.copyOf(starts[1]) + bytes.copyOfRange(starts[2], bytes.size)
        file().writeBytes(cut)
        assertThrows(EncryptedLog.DamagedException::class.java) { log().read() }
    }

    @Test
    fun aChangedByteShows() {
        written(3)
        val bytes = file().readBytes()
        val i = recordStarts(bytes)[1] + 30
        bytes[i] = (bytes[i].toInt() xor 1).toByte()
        file().writeBytes(bytes)
        assertThrows(EncryptedLog.DamagedException::class.java) { log().read() }
    }

    @Test
    fun anotherKeyReadsNothing() {
        written(1)
        assertThrows(EncryptedLog.DamagedException::class.java) { log(k = Ciphers.randomBytes(Cipher256.KEY)).read() }
    }

    // Either slot may be the one a stop broke, the newest included: the other one and the records still give the log.
    @Test
    fun oneBrokenHeaderSlotIsSurvived() {
        for (slot in 0..1) {
            file().delete()
            written(2)
            val bytes = file().readBytes()
            for (i in 25 + slot * 106 until 25 + (slot + 1) * 106) bytes[i] = 0
            file().writeBytes(bytes)
            assertEquals("slot $slot", 2, log().read().size)
        }
    }

    @Test
    fun rewriteKeepsOnlyTheGivenRecords() {
        val l = written(10)
        l.rewrite(listOf(rec(7), rec(9)))
        assertEquals(listOf("record 7", "record 9"), texts(log().read()))
        assertTrue(!File(file().path + ".new").exists())
        l.append(rec(10))
        assertEquals(listOf("record 7", "record 9", "record 10"), texts(log().read()))
    }

    // The caller wiping its key must not leave the log writing under a key of zeros.
    @Test
    fun theLogKeepsItsOwnCopyOfTheKey() {
        val k = key.copyOf()
        val l = EncryptedLog(file(), k) { Cipher256.AES }
        l.read()
        l.append(rec(0))
        k.fill(0)
        l.append(rec(1))
        assertEquals(listOf("record 0", "record 1"), texts(log().read()))
    }

    private fun recordStarts(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var pos = 256
        while (pos + 4 <= bytes.size) {
            out += pos
            pos += 4 + java.nio.ByteBuffer.wrap(bytes, pos, 4).int
        }
        return out
    }

    private fun lastRecordStart(bytes: ByteArray) = recordStarts(bytes).last()
}
