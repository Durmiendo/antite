package dev.antite.keys

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : IOException("wrong password or damaged file")

object BackupCipher {
    private val MAGIC = "ANTITEBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 2
    private const val LEGACY: Byte = 1
    private const val HEADER_SIZE = 8 + 1 + 4 + 4 + 1 + 16 + 8
    private const val LEGACY_HEADER_SIZE = 8 + 1 + 4 + 16 + 8
    const val CHUNK = 1 shl 20
    var params = KeyRing.DEFAULT_KDF

    // A crafted file could ask for so much work or memory that checking the password takes minutes or kills the app.
    private const val MAX_ITERATIONS = 2_000_000
    private val MAX_PARAMS = KeyRing.KdfParams(16, 256 * 1024, 8)

    private fun legacyKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun key(kdf: KeyRing.Kdf, password: CharArray, salt: ByteArray, params: KeyRing.KdfParams): SecretKeySpec {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        encoded.clear()
        while (encoded.hasRemaining()) encoded.put(0)
        val derived = try {
            kdf.derive(bytes, salt, params)
        } finally {
            bytes.fill(0)
        }
        return SecretKeySpec(derived, 0, 32, "AES").also { derived.fill(0) }
    }

    fun encrypt(out: OutputStream, password: CharArray, kdf: KeyRing.Kdf): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonceBase = ByteArray(8).also(random::nextBytes)
        val p = params
        val header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).put(VERSION)
            .putInt(p.iterations).putInt(p.memoryKib).put(p.parallelism.toByte()).put(salt).put(nonceBase).array()
        out.write(header)
        return ChunkWriter(out, key(kdf, password, salt, p), header, nonceBase)
    }

    fun decrypt(input: InputStream, password: CharArray, kdf: KeyRing.Kdf): InputStream {
        val data = DataInputStream(input)
        val start = ByteArray(9)
        try {
            data.readFully(start)
        } catch (e: EOFException) {
            throw IOException("not an Antite backup")
        }
        if (!start.copyOf(8).contentEquals(MAGIC)) throw IOException("not an Antite backup")
        val version = start[8]
        val size = when (version) {
            VERSION -> HEADER_SIZE
            LEGACY -> LEGACY_HEADER_SIZE
            else -> throw IOException("unsupported backup version $version")
        }
        val header = start.copyOf(size)
        try {
            data.readFully(header, 9, size - 9)
        } catch (e: EOFException) {
            throw IOException("not an Antite backup")
        }
        val b = ByteBuffer.wrap(header, 9, size - 9)
        val key = if (version == LEGACY) {
            val iterations = b.getInt()
            if (iterations !in 1..MAX_ITERATIONS) throw IOException("bad header")
            legacyKey(password, ByteArray(16).also { b.get(it) }, iterations)
        } else {
            val p = KeyRing.KdfParams(b.getInt(), b.getInt(), b.get().toInt())
            if (p.iterations !in 1..MAX_PARAMS.iterations || p.memoryKib !in 8..MAX_PARAMS.memoryKib ||
                p.parallelism !in 1..MAX_PARAMS.parallelism) throw IOException("bad header")
            key(kdf, password, ByteArray(16).also { b.get(it) }, p)
        }
        val nonceBase = ByteArray(8).also { b.get(it) }
        return ChunkReader(data, key, header, nonceBase)
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
