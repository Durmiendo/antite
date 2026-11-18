package dev.antite.keys

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

// An append-only file of sealed records. Each record is sealed on its own; its AAD holds the log id, the record's number
// and the hash of the record before it, so records cannot be dropped, reordered or moved between logs unnoticed. The
// header keeps the count and the last hash sealed in two slots written in turn, so a cut tail shows and a stop while the
// header is written leaves the other slot. After a stop in the middle of an append the unfinished tail is cut off; a
// whole record that the header does not count yet is taken in.
//   header: "ANTLOG01", version, log id (16), slot 0, slot 1, padding to HEADER
//   slot:   length 1, box(seq 8, count 8, last hash 32), aad = log id + slot number
//   record: length 4, box(record), aad = log id + number 8 + previous hash
class EncryptedLog(private val disk: Disk, private val file: File, key: ByteArray, private val cipher: () -> Cipher256) {
    // Its own copy: the caller may wipe its key, and close() wipes this one.
    private val key = key.copyOf()

    class DamagedException(message: String) : IOException(message)

    companion object {
        private val MAGIC = "ANTLOG01".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 1
        // Room for the longest box, the cascade's two tags; the first byte says how long this one is.
        private const val SLOT = 1 + 1 + 24 + 48 + 2 * Cipher256.TAG
        private const val HEADER = 256
        private const val MAX_RECORD = 1 shl 20
        private val NO_HASH = ByteArray(32)

        private fun sha256(b: ByteArray, off: Int = 0, len: Int = b.size) =
            MessageDigest.getInstance("SHA-256").apply { update(b, off, len) }.digest()
    }

    private var logId = ByteArray(16)
    private var seq = 0L
    private var count = 0L
    private var lastHash = NO_HASH
    private var end = HEADER.toLong()
    private var opened = false
    private var seen = HashSet<Cipher256>()

    // The ciphers of the records read and appended since: what rewrite() would seal again.
    val ciphers: Set<Cipher256> get() = seen

    // Every record in order. A missing file reads as an empty log.
    fun read(): List<ByteArray> {
        opened = true
        if (!disk.exists(file)) {
            create()
            return emptyList()
        }
        disk.open(file, Disk.Open.WRITE).use { f ->
            val head = ByteArray(HEADER)
            if (f.length < HEADER) throw DamagedException("no header")
            f.readFully(0, head)
            if (!head.copyOf(8).contentEquals(MAGIC) || head[8] != VERSION) throw DamagedException("not a log")
            logId = head.copyOfRange(9, 25)
            val slots = (0..1).mapNotNull { i -> slot(head, i) }
            val best = slots.maxByOrNull { it.first } ?: throw DamagedException("header unreadable")
            seq = best.first
            val counted = best.second
            val countedHash = best.third
            val out = ArrayList<ByteArray>()
            var pos = HEADER.toLong()
            var prev = NO_HASH
            var n = 0L
            val size = f.length
            while (pos + 4 <= size) {
                val len = ByteArray(4).also { f.readFully(pos, it) }.let { ByteBuffer.wrap(it).int }
                if (len <= 0 || len > MAX_RECORD || pos + 4 + len > size) break
                val boxed = ByteArray(len).also { f.readFully(pos + 4, it) }
                val plain = try {
                    Ciphers.unbox(key, boxed, aad(n, prev))
                } catch (e: IOException) {
                    // Damage counted by the header is damage; past it, an append that did not finish.
                    if (n < counted) throw DamagedException("record $n damaged")
                    break
                }
                out += plain
                seen += Ciphers.boxCipher(boxed)
                prev = sha256(boxed)
                pos += 4 + len
                n++
            }
            if (n < counted) throw DamagedException("records missing: $n of $counted")
            if (n == counted && !prev.contentEquals(countedHash)) throw DamagedException("records replaced")
            count = n
            lastHash = prev
            end = pos
            if (size > pos) f.truncate(pos)
            if (n > counted) writeSlot(f)
            return out
        }
    }

    fun append(record: ByteArray) = appendAll(listOf(record))

    // Several records with one sync of the disk: a stop in the middle keeps the whole ones before it.
    fun appendAll(records: List<ByteArray>) {
        check(opened) { "read the log first" }
        if (records.isEmpty()) return
        disk.open(file, Disk.Open.WRITE).use { f ->
            for (r in records) {
                val c = cipher()
                val boxed = Ciphers.box(c, key, r, aad(count, lastHash))
                seen += c
                f.write(end, ByteBuffer.allocate(4 + boxed.size).putInt(boxed.size).put(boxed).array())
                end += 4 + boxed.size
                count++
                lastHash = sha256(boxed)
            }
            f.sync()
            writeSlot(f)
        }
    }

    fun close() = key.fill(0)

    // The log written again with only these records, under a new id: aside first, then renamed over the old one.
    fun rewrite(records: List<ByteArray>) {
        val tmp = File(file.path + ".new")
        val next = EncryptedLog(disk, tmp, key, cipher)
        disk.delete(tmp)
        next.read()
        next.appendAll(records)
        next.close()
        disk.renameDurably(tmp, file)
        logId = next.logId
        seq = next.seq
        count = next.count
        lastHash = next.lastHash
        end = next.end
        seen = next.seen
        opened = true
    }

    private fun create() {
        logId = Ciphers.randomBytes(16)
        seq = 0
        count = 0
        lastHash = NO_HASH
        end = HEADER.toLong()
        // Made aside and renamed in: a stop half way must not leave a log without a header.
        val fresh = File(file.path + ".create")
        disk.mkdirs(file.absoluteFile.parentFile)
        disk.open(fresh, Disk.Open.WRITE).use { f ->
            f.truncate(0)
            f.write(0, ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).put(logId).array())
            writeSlot(f)
        }
        disk.renameDurably(fresh, file)
    }

    private fun aad(n: Long, prev: ByteArray) = ByteBuffer.allocate(16 + 8 + 32).put(logId).putLong(n).put(prev).array()

    private fun slotAad(i: Int) = logId + byteArrayOf(i.toByte())

    private fun slot(head: ByteArray, i: Int): Triple<Long, Long, ByteArray>? = runCatching {
        val at = 25 + i * SLOT
        val len = head[at].toInt() and 0xff
        require(len in 1 until SLOT)
        val plain = Ciphers.unbox(key, head.copyOfRange(at + 1, at + 1 + len), slotAad(i))
        val b = ByteBuffer.wrap(plain)
        Triple(b.long, b.long, ByteArray(32).also { b.get(it) })
    }.getOrNull()

    private fun writeSlot(f: Disk.Handle) {
        seq++
        val i = (seq % 2).toInt()
        val plain = ByteBuffer.allocate(48).putLong(seq).putLong(count).put(lastHash).array()
        val box = Ciphers.box(cipher(), key, plain, slotAad(i))
        f.write((25 + i * SLOT).toLong(), byteArrayOf(box.size.toByte()) + box)
        f.sync()
    }
}
