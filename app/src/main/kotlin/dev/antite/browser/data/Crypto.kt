package dev.antite.browser.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
        val k = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        key = k
        return k
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(MODE)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteBuffer.allocate(1 + iv.size + body.size).put(iv.size.toByte()).put(iv).put(body)
        return Base64.encodeToString(out.array(), Base64.NO_WRAP)
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
