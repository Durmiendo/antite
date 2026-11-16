package dev.antite.browser.data

import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// The device key of the time before the key ring (docs/decisions/0008): only read, to move old data over, then destroyed.
object Crypto {
    private const val ALIAS = "antite-vault"
    private const val STORE = "AndroidKeyStore"
    private const val MODE = "AES/GCM/NoPadding"
    private var key: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        key?.let { return it }
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val k = existing ?: throw java.security.KeyStoreException("no legacy key")
        key = k
        return k
    }

    @Synchronized
    fun forget() {
        key = null
        runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    fun open(sealed: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(sealed)
        val iv = ByteArray(input.get().toInt()).also { input.get(it) }
        val body = ByteArray(input.remaining()).also { input.get(it) }
        val c = Cipher.getInstance(MODE)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(body)
    }

    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        return try {
            val input = ByteBuffer.wrap(Base64.decode(stored, Base64.NO_WRAP))
            val iv = ByteArray(input.get().toInt()).also { input.get(it) }
            val body = ByteArray(input.remaining()).also { input.get(it) }
            val c = Cipher.getInstance(MODE)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("Antite", "decrypt failed", e)
            null
        }
    }
}
