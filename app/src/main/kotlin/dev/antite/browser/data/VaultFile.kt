package dev.antite.browser.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// The format of a file in the secure storage (docs/decisions/0006): a header with the file's own key wrapped by the
// device key, then AES-256-GCM chunks of 64 KiB that can be read from any position.
object VaultFile {
    const val CHUNK = 64 * 1024
    const val TAG = 16
    const val HEADER = 70
    private const val VERSION = 1
    private const val KEY_DEVICE = 1
    private const val KEY_OPEN = 2
    private const val KEY_SLOT = 63
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())

    // How a file key is wrapped for this device and unwrapped again.
    class Keys(val wrap: (ByteArray) -> ByteArray, val unwrap: (ByteArray) -> ByteArray)

    private fun header(form: Int, blob: ByteArray): ByteArray {
        if (blob.size > KEY_SLOT) throw IOException("key blob too long: ${blob.size}")
        return ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION.toByte()).put(form.toByte()).put(blob.size.toByte())
            .put(blob).array()
    }

    private class Head(val form: Int, val blob: ByteArray)

    private fun parse(h: ByteArray): Head {
        if (h.size < HEADER || !h.copyOf(4).contentEquals(MAGIC)) throw IOException("not a vault file")
        if (h[4].toInt() != VERSION) throw IOException("unsupported vault file version ${h[4]}")
        val n = h[6].toInt()
        if (n !in 1..KEY_SLOT) throw IOException("bad key blob")
        return Head(h[5].toInt(), h.copyOfRange(7, 7 + n))
    }

    private fun key(head: Head, keys: Keys): ByteArray = when (head.form) {
        KEY_DEVICE -> keys.unwrap(head.blob)
        else -> throw IOException("vault file key is not for this device")
    }

    private fun crypt(mode: Int, key: ByteArray, index: Long, last: Boolean, data: ByteArray, off: Int, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, ByteBuffer.allocate(12).putLong(index).array()))
        c.updateAAD(ByteBuffer.allocate(14).put(MAGIC).put(VERSION.toByte()).putLong(index).put(if (last) 1 else 0).array())
        return c.doFinal(data, off, len)
    }

    // Encrypts what is written; the file is complete only after close().
    fun writer(out: OutputStream, keys: Keys): OutputStream {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        out.write(header(KEY_DEVICE, keys.wrap(key)))
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
                out.write(crypt(Cipher.ENCRYPT_MODE, key, index++, last, buf, 0, filled))
                filled = 0
            }

            override fun flush() = out.flush()

            override fun close() {
                if (closed) return
                closed = true
                flushChunk(true)
                out.close()
            }
        }
    }

    // The length of the content of a complete file of this size.
    fun plainLength(fileLength: Long): Long {
        val body = fileLength - HEADER
        if (body < TAG) throw IOException("vault file too short")
        val chunks = (body + CHUNK + TAG - 1) / (CHUNK + TAG)
        return body - chunks * TAG
    }

    class Reader(file: File, keys: Keys) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val key: ByteArray
        val length: Long
        private val chunks: Long
        private var cached = -1L
        private var plain = ByteArray(0)

        init {
            try {
                val h = ByteArray(HEADER)
                raf.readFully(h)
                key = key(parse(h), keys)
                length = plainLength(raf.length())
                chunks = maxOf(1, (length + CHUNK - 1) / CHUNK)
            } catch (e: Exception) {
                raf.close()
                throw e
            }
        }

        private fun load(index: Long) {
            if (index == cached) return
            val last = index == chunks - 1
            val size = (if (last) length - index * CHUNK else CHUNK.toLong()).toInt() + TAG
            if (size < TAG) throw IOException("vault file is damaged at chunk $index")
            val data = ByteArray(size)
            raf.seek(HEADER + index * (CHUNK + TAG))
            raf.readFully(data)
            plain = try {
                crypt(Cipher.DECRYPT_MODE, key, index, last, data, 0, size)
            } catch (e: java.security.GeneralSecurityException) {
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
        override fun close() = raf.close()
    }

    // For the backup file: the same file with its key in the open, readable on any device. The backup itself is
    // encrypted, so the open key never lies on the disk.
    fun forBackup(file: File, keys: Keys): InputStream {
        val input = file.inputStream()
        try {
            val h = ByteArray(HEADER)
            if (input.readNBytes(h, 0, HEADER) != HEADER) throw IOException("vault file too short")
            return SequenceInputStream(header(KEY_OPEN, key(parse(h), keys)).inputStream(), input)
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    // After a restore: the open key is wrapped for this device, in place.
    fun adopt(file: File, keys: Keys) {
        RandomAccessFile(file, "rw").use { raf ->
            val h = ByteArray(HEADER)
            raf.readFully(h)
            val head = parse(h)
            if (head.form != KEY_OPEN) throw IOException("vault file key is not open")
            raf.seek(0)
            raf.write(header(KEY_DEVICE, keys.wrap(head.blob)))
        }
    }
}
