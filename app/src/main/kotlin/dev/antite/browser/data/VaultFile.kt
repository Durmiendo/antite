package dev.antite.browser.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

// The format of a file in the secure storage: a 70-byte header naming the cipher, then
// chunks of 64 KiB, each sealed on its own, so the file can be read from any position. The file's key lives in the
// vault index. Version 1 files carried their key in the header, wrapped by the old device key; they are read only
// to move them over, which takes a header rewrite: their AES chunks are the same as in version 2.
object VaultFile {
    const val CHUNK = 64 * 1024
    const val HEADER = 70
    private const val VERSION = 2
    private const val V1_KEY_DEVICE = 1
    private const val V1_KEY_OPEN = 2
    private const val V1_KEY_SLOT = 63
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())

    class Head(val version: Int, val cipher: Cipher256, val v1Form: Int = 0, val v1Blob: ByteArray = ByteArray(0))

    private fun header(cipher: Cipher256) =
        ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION.toByte()).put(cipher.id.toByte()).array()

    fun head(h: ByteArray): Head {
        if (h.size < HEADER || !h.copyOf(4).contentEquals(MAGIC)) throw IOException("not a vault file")
        return when (h[4].toInt()) {
            1 -> {
                val n = h[6].toInt()
                if (n !in 1..V1_KEY_SLOT) throw IOException("bad key blob")
                Head(1, Cipher256.AES, h[5].toInt(), h.copyOfRange(7, 7 + n))
            }
            VERSION -> Head(VERSION, Cipher256.of(h[5].toInt()))
            else -> throw IOException("unsupported vault file version ${h[4]}")
        }
    }

    fun head(file: File): Head = RandomAccessFile(file, "r").use { raf -> ByteArray(HEADER).also { raf.readFully(it) }.let(::head) }

    // The key of a version 1 file, as a 64-byte key of this format. unwrap opens the old device-wrapped form.
    fun v1Key(head: Head, unwrap: (ByteArray) -> ByteArray): ByteArray {
        val k = when (head.v1Form) {
            V1_KEY_DEVICE -> unwrap(head.v1Blob)
            V1_KEY_OPEN -> head.v1Blob
            else -> throw IOException("unknown key form ${head.v1Form}")
        }
        return k.copyOf(Cipher256.KEY).also { k.fill(0) }
    }

    // A version 1 file becomes a version 2 one: its chunks are already AES under the same key and nonces.
    fun upgradeHeader(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(Cipher256.AES))
            raf.fd.sync()
        }
    }

    // Chunk nonces only need to differ within a file: every file has its own random key.
    private fun nonce(index: Long) = ByteBuffer.allocate(24).putLong(index).array()

    // The same associated data as version 1, so its chunks stay valid.
    private fun aad(index: Long, last: Boolean) =
        ByteBuffer.allocate(14).put(MAGIC).put(1).putLong(index).put(if (last) 1 else 0).array()

    // Encrypts what is written; the file is complete only after close(). The key is the caller's to wipe.
    fun writer(out: OutputStream, cipher: Cipher256, key: ByteArray): OutputStream {
        out.write(header(cipher))
        return object : OutputStream() {
            private val buf = ByteArray(CHUNK)
            private var filled = 0
            private var index = 0L
            private var closed = false

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off
                var left = len
                while (left > 0) {
                    // A full buffer is written only when more data comes: the last chunk must be marked as last.
                    if (filled == CHUNK) flushChunk(false)
                    val n = minOf(left, CHUNK - filled)
                    System.arraycopy(b, o, buf, filled, n)
                    filled += n
                    o += n
                    left -= n
                }
            }

            private fun flushChunk(last: Boolean) {
                out.write(Ciphers.seal(cipher, key, nonce(index), aad(index, last), buf, 0, filled))
                index++
                filled = 0
            }

            override fun flush() = out.flush()

            override fun close() {
                if (closed) return
                closed = true
                flushChunk(true)
                buf.fill(0)
                out.close()
            }
        }
    }

    // The length of the content of a complete file of this size.
    fun plainLength(fileLength: Long, cipher: Cipher256): Long {
        val tag = cipher.overhead
        val body = fileLength - HEADER
        if (body < tag) throw IOException("vault file too short")
        val chunks = (body + CHUNK + tag - 1) / (CHUNK + tag)
        return body - chunks * tag
    }

    // Reads with a copy of the key, wiped on close().
    class Reader(file: File, key: ByteArray) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val key = key.copyOf()
        private val cipher: Cipher256
        val length: Long
        private val chunks: Long
        private var cached = -1L
        private var plain = ByteArray(0)

        init {
            try {
                val h = ByteArray(HEADER)
                raf.readFully(h)
                cipher = head(h).cipher
                length = plainLength(raf.length(), cipher)
                chunks = maxOf(1, (length + CHUNK - 1) / CHUNK)
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        private fun load(index: Long) {
            if (index == cached) return
            val last = index == chunks - 1
            val size = (if (last) length - index * CHUNK else CHUNK.toLong()).toInt() + cipher.overhead
            if (size < cipher.overhead) throw IOException("vault file is damaged at chunk $index")
            val data = ByteArray(size)
            raf.seek(HEADER + index * (CHUNK + cipher.overhead))
            raf.readFully(data)
            plain.fill(0)
            plain = try {
                Ciphers.open(cipher, key, nonce(index), aad(index, last), data, 0, size)
            } catch (e: IOException) {
                throw IOException("vault file is damaged at chunk $index", e)
            }
            cached = index
        }

        // Up to len bytes from the position; -1 at the end.
        @Synchronized
        fun read(position: Long, b: ByteArray, off: Int, len: Int): Int {
            if (position >= length) return -1
            if (len == 0) return 0
            val index = position / CHUNK
            load(index)
            val at = (position - index * CHUNK).toInt()
            val n = minOf(len, plain.size - at)
            System.arraycopy(plain, at, b, off, n)
            return n
        }

        fun stream(): InputStream = object : InputStream() {
            private var pos = 0L

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                this@Reader.read(pos, b, off, len).also { if (it > 0) pos += it }

            override fun close() = this@Reader.close()
        }

        @Synchronized
        override fun close() {
            key.fill(0)
            plain.fill(0)
            raf.close()
        }
    }

    // Overwrites the file with zeros and syncs before it is deleted. On flash memory this guarantees nothing by
    // itself; the guarantee comes from destroying the key.
    fun wipe(file: File) {
        if (!file.exists()) return
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val zeros = ByteArray(CHUNK)
                var left = raf.length()
                raf.seek(0)
                while (left > 0) {
                    val n = minOf(left, CHUNK.toLong()).toInt()
                    raf.write(zeros, 0, n)
                    left -= n
                }
                raf.fd.sync()
            }
        }
        file.delete()
    }
}
