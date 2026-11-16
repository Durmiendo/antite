package dev.antite.browser.data

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// The ciphers of docs/decisions/0008. Keys are 64 bytes: AES takes the first half, XChaCha20 the second, the cascade
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

object Ciphers {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun aes(mode: Int, key: ByteArray, nonce12: ByteArray, aad: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, 0, 32, "AES"), GCMParameterSpec(TAG_BITS, nonce12))
        c.updateAAD(aad)
        return c.doFinal(data, off, len)
    }

    private const val TAG_BITS = 128

    // Android's Conscrypt and the desktop JDK name the same cipher differently.
    private fun chachaCipher(): Cipher = try {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    } catch (e: GeneralSecurityException) {
        Cipher.getInstance("ChaCha20-Poly1305")
    }

    // XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha): a subkey from HChaCha20 over the first 16 nonce bytes, then the
    // IETF cipher with four zero bytes and the last 8 nonce bytes.
    private fun xchacha(mode: Int, key: ByteArray, nonce24: ByteArray, aad: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray {
        val sub = hchacha20(key.copyOfRange(32, 64), nonce24.copyOfRange(0, 16))
        val iv = ByteArray(12).also { System.arraycopy(nonce24, 16, it, 4, 8) }
        try {
            val c = chachaCipher()
            c.init(mode, SecretKeySpec(sub, "ChaCha20"), IvParameterSpec(iv))
            c.updateAAD(aad)
            return c.doFinal(data, off, len)
        } finally {
            sub.fill(0)
        }
    }

    // Seals one piece. The nonce is 24 bytes; AES uses its first 12, which callers keep unique for the key.
    fun seal(cipher: Cipher256, key: ByteArray, nonce24: ByteArray, aad: ByteArray, data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray =
        when (cipher) {
            Cipher256.AES -> aes(Cipher.ENCRYPT_MODE, key, nonce24.copyOf(12), aad, data, off, len)
            Cipher256.XCHACHA -> xchacha(Cipher.ENCRYPT_MODE, key, nonce24, aad, data, off, len)
            Cipher256.CASCADE -> {
                val inner = xchacha(Cipher.ENCRYPT_MODE, key, nonce24, aad, data, off, len)
                aes(Cipher.ENCRYPT_MODE, key, nonce24.copyOf(12), aad, inner, 0, inner.size).also { inner.fill(0) }
            }
        }

    fun open(cipher: Cipher256, key: ByteArray, nonce24: ByteArray, aad: ByteArray, data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray =
        try {
            when (cipher) {
                Cipher256.AES -> aes(Cipher.DECRYPT_MODE, key, nonce24.copyOf(12), aad, data, off, len)
                Cipher256.XCHACHA -> xchacha(Cipher.DECRYPT_MODE, key, nonce24, aad, data, off, len)
                Cipher256.CASCADE -> {
                    val inner = aes(Cipher.DECRYPT_MODE, key, nonce24.copyOf(12), aad, data, off, len)
                    xchacha(Cipher.DECRYPT_MODE, key, nonce24, aad, inner, 0, inner.size).also { inner.fill(0) }
                }
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("damaged or not for this key", e)
        }

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

    fun hchacha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        val k = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 8) s[4 + i] = k.getInt(i * 4)
        val n = ByteBuffer.wrap(nonce16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) s[12 + i] = n.getInt(i * 4)
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13); quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12); quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
        val out = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) out.putInt(s[i])
        for (i in 12 until 16) out.putInt(s[i])
        s.fill(0)
        return out.array()
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }
}
