package dev.antite.browser.transfer

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : IOException("wrong password or damaged file")

object BackupCipher {
    private val MAGIC = "ANTITEBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 8 + 1 + 4 + 16 + 8
    const val CHUNK = 1 shl 20
    var iterations = 600_000

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(out: OutputStream, password: CharArray): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonceBase = ByteArray(8).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(nonceBase).array()
        out.write(header)
        return ChunkWriter(out, key(password, salt, iterations), header, nonceBase)
    }

    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER_SIZE)
        try {
            data.readFully(header)
        } catch (e: EOFException) {
            throw IOException("not an Antite backup")
        }
        val b = ByteBuffer.wrap(header)
        val magic = ByteArray(8).also { b.get(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("not an Antite backup")
        val version = b.get()
        if (version != VERSION) throw IOException("unsupported backup version $version")
        val iterations = b.getInt()
        if (iterations !in 1..10_000_000) throw IOException("bad header")
        val salt = ByteArray(16).also { b.get(it) }
        val nonceBase = ByteArray(8).also { b.get(it) }
        return ChunkReader(data, key(password, salt, iterations), header, nonceBase)
    }

    private fun nonce(base: ByteArray, index: Int) = ByteBuffer.allocate(12).put(base).putInt(index).array()

    private fun aad(header: ByteArray, last: Boolean) = header + byteArrayOf(if (last) 1 else 0)

    private class ChunkWriter(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val nonceBase: ByteArray,
    ) : OutputStream() {
        private val buf = ByteArrayOutputStream(CHUNK)
        private var index = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - buf.size())
                buf.write(b, o, n)
                o += n
                left -= n
                if (buf.size() == CHUNK) flushChunk(false)
            }
        }

        private fun flushChunk(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(nonceBase, index++)))
            c.updateAAD(aad(header, last))
            val sealed = c.doFinal(buf.toByteArray())
            buf.reset()
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            flushChunk(true)
            out.close()
        }
    }

    private class ChunkReader(
        private val data: DataInputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val nonceBase: ByteArray,
    ) : InputStream() {
        private var chunk = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        private fun next(): Boolean {
            if (done) return false
            val size = try {
                data.readInt()
            } catch (e: EOFException) {
                throw WrongPasswordException()
            }
            if (size < 16 || size > CHUNK + 16) throw WrongPasswordException()
            val sealed = ByteArray(size)
            try {
                data.readFully(sealed)
            } catch (e: EOFException) {
                throw WrongPasswordException()
            }
            val nonce = nonce(nonceBase, index++)
            chunk = open(sealed, nonce, false) ?: open(sealed, nonce, true)?.also { done = true } ?: throw WrongPasswordException()
            pos = 0
            if (done && data.read() != -1) throw WrongPasswordException()
            return true
        }

        private fun open(sealed: ByteArray, nonce: ByteArray, last: Boolean): ByteArray? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            c.updateAAD(aad(header, last))
            c.doFinal(sealed)
        } catch (e: javax.crypto.AEADBadTagException) {
            null
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) if (!next()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = data.close()
    }
}
