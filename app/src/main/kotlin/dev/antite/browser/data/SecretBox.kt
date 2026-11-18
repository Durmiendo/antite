package dev.antite.browser.data

import android.util.Base64
import dev.antite.keys.Cipher256
import dev.antite.keys.Ciphers
import dev.antite.keys.CoreKey
import java.io.IOException

// A saved secret (a password, an address, a card number) as it is stored in vault.db: base64 of a box under the
// secrets key of the key ring.
object SecretBox {
    private val AAD = "antite-secret".toByteArray()

    // A value of the version before the key ring: base64 of the IV length (always 12), the IV, the sealed bytes.
    fun isLegacy(raw: ByteArray) = raw.isNotEmpty() && raw[0].toInt() == 12

    fun seal(key: CoreKey, cipher: Cipher256, plain: String): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        try {
            return Base64.encodeToString(Ciphers.box(cipher, key, bytes, AAD), Base64.NO_WRAP)
        } finally {
            bytes.fill(0)
        }
    }

    fun open(key: CoreKey, stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        if (isLegacy(raw)) return Crypto.decrypt(stored) ?: throw IOException("legacy value unreadable")
        val plain = Ciphers.unbox(key, raw, AAD)
        try {
            return String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }
}
