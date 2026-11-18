package dev.antite.keys

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class WrongPasswordException : IOException("wrong password or damaged file")

// The backup file. Version 3 is sealed in the key core with the cipher named in its header and the whole 64-byte
// Argon2id key; versions 2 (Argon2id, AES-256-GCM) and 1 (PBKDF2, AES-256-GCM) are only read, also through the core.
object BackupCipher {
    private val MAGIC = "ANTITEBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 3
    private const val V2: Byte = 2
    private const val LEGACY: Byte = 1
    private const val HEADER_SIZE = 8 + 1 + 1 + 4 + 4 + 1 + 16 + 8
    private const val V2_HEADER_SIZE = 8 + 1 + 4 + 4 + 1 + 16 + 8
    private const val LEGACY_HEADER_SIZE = 8 + 1 + 4 + 16 + 8
    const val CHUNK = 1 shl 20
    var params = KeyRing.DEFAULT_KDF

    // A crafted file could ask for so much work or memory that checking the password takes minutes or kills the app.
    private const val MAX_ITERATIONS = 2_000_000
    private val MAX_PARAMS = KeyRing.KdfParams(16, 256 * 1024, 8)

    // Version 1 keys are AES keys of 32 bytes: the AES half of a core key, the XChaCha half left zero and unused.
    private fun legacyKey(password: CharArray, salt: ByteArray, iterations: Int): CoreKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return CoreKey.import(ByteArray(Cipher256.KEY).also { System.arraycopy(bytes, 0, it, 0, 32) }).also { bytes.fill(0) }
    }

    // Version 2 used the first 32 bytes of the derived key for AES: the AES half of the same key in the core.
    private fun derive(kdf: KeyRing.Kdf, password: CharArray, salt: ByteArray, params: KeyRing.KdfParams): CoreKey {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        encoded.clear()
        while (encoded.hasRemaining()) encoded.put(0)
        try {
            return kdf.derive(bytes, salt, params)
        } finally {
            bytes.fill(0)
        }
    }

    fun encrypt(out: OutputStream, password: CharArray, kdf: KeyRing.Kdf, cipher: Cipher256 = Cipher256.XCHACHA): Writer {
        val salt = Ciphers.randomBytes(16)
        val nonceBase = Ciphers.randomBytes(8)
        val p = params
        val header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).put(VERSION).put(cipher.id.toByte())
            .putInt(p.iterations).putInt(p.memoryKib).put(p.parallelism.toByte()).put(salt).put(nonceBase).array()
        val key = derive(kdf, password, salt, p)
        try {
            out.write(header)
        } catch (e: Throwable) {
            key.close()
            throw e
        }
        return Writer(out, cipher, key, header, nonceBase)
    }

    fun decrypt(input: InputStream, password: CharArray, kdf: KeyRing.Kdf): Reader {
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
            V2 -> V2_HEADER_SIZE
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
        val cipher = if (version == VERSION) Cipher256.of(b.get().toInt()) else Cipher256.AES
        val key = if (version == LEGACY) {
            val iterations = b.getInt()
            if (iterations !in 1..MAX_ITERATIONS) throw IOException("bad header")
            legacyKey(password, ByteArray(16).also { b.get(it) }, iterations)
        } else {
            val p = KeyRing.KdfParams(b.getInt(), b.getInt(), b.get().toInt())
            if (p.iterations !in 1..MAX_PARAMS.iterations || p.memoryKib !in 8..MAX_PARAMS.memoryKib ||
                p.parallelism !in 1..MAX_PARAMS.parallelism) throw IOException("bad header")
            derive(kdf, password, ByteArray(16).also { b.get(it) }, p)
        }
        val nonceBase = ByteArray(8).also { b.get(it) }
        return Reader(data, cipher, key, header, nonceBase)
    }

    // Unique within the file, and the key is the file's own (fresh salt): the base and the chunk number in the 12
    // bytes AES reads, the rest zero.
    private fun nonce(base: ByteArray, index: Int) = ByteBuffer.allocate(24).put(base).putInt(index).array()

    private fun aad(header: ByteArray, last: Boolean) = header + byteArrayOf(if (last) 1 else 0)

    // A data key carried by the backup is boxed under the backup's key, bound to this file by its header.
    private fun keyAad(header: ByteArray) = header + "keyring".toByteArray()

    class Writer internal constructor(
        private val out: OutputStream,
        private val cipher: Cipher256,
        private val key: CoreKey,
        private val header: ByteArray,
        private val nonceBase: ByteArray,
    ) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var filled = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - filled)
                System.arraycopy(b, o, buf, filled, n)
                filled += n
                o += n
                left -= n
                if (filled == CHUNK) flushChunk(false)
            }
        }

        private fun flushChunk(last: Boolean) {
            val sealed = Ciphers.seal(cipher, key, nonce(nonceBase, index++), aad(header, last), buf, 0, filled)
            buf.fill(0, 0, filled)
            filled = 0
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
        }

        override fun flush() = out.flush()

        // A data key for the backup: only its box under the backup's key leaves the core.
        fun sealKey(k: CoreKey): ByteArray = k.wrapUnder(key, cipher, keyAad(header))

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushChunk(true)
            } finally {
                key.close()
                buf.fill(0)
                out.close()
            }
        }
    }

    class Reader internal constructor(
        private val data: DataInputStream,
        private val cipher: Cipher256,
        private val key: CoreKey,
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
            if (size < cipher.overhead || size > CHUNK + cipher.overhead) throw WrongPasswordException()
            val sealed = ByteArray(size)
            try {
                data.readFully(sealed)
            } catch (e: EOFException) {
                throw WrongPasswordException()
            }
            val nonce = nonce(nonceBase, index++)
            chunk.fill(0)
            chunk = open(sealed, nonce, false) ?: open(sealed, nonce, true)?.also { done = true } ?: throw WrongPasswordException()
            pos = 0
            if (done && data.read() != -1) throw WrongPasswordException()
            return true
        }

        private fun open(sealed: ByteArray, nonce: ByteArray, last: Boolean): ByteArray? =
            KeyCore.openWith(cipher.id, key.handle, nonce, aad(header, last), sealed, 0, sealed.size, false)

        // A data key boxed by Writer.sealKey, straight into the core; null when the box does not belong to this file.
        fun openKey(box: ByteArray): CoreKey? = CoreKey.unwrap(key, box, keyAad(header))

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

        override fun close() {
            key.close()
            chunk.fill(0)
            data.close()
        }
    }
}
