package dev.antite.keys

import java.io.IOException
import java.nio.ByteBuffer

// The ciphers of the key ring. Keys are 64 bytes: AES takes the first half, XChaCha20 the second, the cascade
// both, so a key never has to change size when the cipher setting does.
enum class Cipher256(val id: Int) {
    AES(1), XCHACHA(2), CASCADE(3);

    companion object {
        const val KEY = 64
        const val TAG = 16
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: throw IOException("unknown cipher $id")
    }

    // Bytes added to each sealed piece.
    val overhead get() = if (this == CASCADE) 2 * TAG else TAG
}

// The ciphers themselves run in the native key core.
object Ciphers {
    fun randomBytes(n: Int) = ByteArray(n).also { KeyCore.random(it) }

    // Seals one piece. The nonce is 24 bytes; AES uses its first 12, which callers keep unique for the key.
    fun seal(cipher: Cipher256, key: ByteArray, nonce24: ByteArray, aad: ByteArray, data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray =
        KeyCore.seal(cipher.id, key, nonce24, aad, data, off, len, false)

    fun open(cipher: Cipher256, key: ByteArray, nonce24: ByteArray, aad: ByteArray, data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray =
        KeyCore.open(cipher.id, key, nonce24, aad, data, off, len, false) ?: throw IOException("damaged or not for this key")

    // A small self-contained box for a long-lived key: cipher id, a random nonce, the sealed bytes. Random nonces
    // are safe here: 24 bytes for XChaCha, and AES boxes under one key stay far below the 2^32 limit.
    fun box(cipher: Cipher256, key: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = randomBytes(24)
        val body = seal(cipher, key, nonce, aad, plain)
        return ByteBuffer.allocate(1 + 24 + body.size).put(cipher.id.toByte()).put(nonce).put(body).array()
    }

    fun unbox(key: ByteArray, boxed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        if (boxed.size < 1 + 24 + Cipher256.TAG) throw IOException("box too short")
        return open(Cipher256.of(boxed[0].toInt()), key, boxed.copyOfRange(1, 25), aad, boxed, 25, boxed.size - 25)
    }

    fun boxCipher(boxed: ByteArray) = Cipher256.of(boxed[0].toInt())
}
