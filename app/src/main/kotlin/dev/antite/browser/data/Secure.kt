package dev.antite.browser.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.antite.keys.CategoryKeys
import dev.antite.keys.Cipher256
import dev.antite.keys.CoreKey
import dev.antite.keys.Disk
import dev.antite.keys.KeyCore
import dev.antite.keys.KeyRing
import dev.antite.keys.RealDisk
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// The app's key ring with the Android Keystore as the device key and Argon2id for passwords,
// and the settings that go with it.
object Secure {
    private const val STORE = "AndroidKeyStore"
    const val PREF_CIPHER = "secureCipher"
    const val PREF_WIPE = "secureWipeZeros"
    // How long after the device unlock a locked category's device key may be used.
    const val AUTH_SECONDS = 30

    @Volatile
    private var ring: KeyRing? = null

    // Bumped after a change of the key mode, the password or the cipher, for the pages that show them.
    val revision = androidx.compose.runtime.mutableIntStateOf(0)

    @Volatile
    private var disk: Disk? = null

    // The app's one way to its private folder for keys and encrypted stores.
    fun disk(context: Context): Disk = disk ?: synchronized(this) {
        disk ?: RealDisk(context.applicationContext.dataDir).also { disk = it }
    }

    fun ring(context: Context): KeyRing = ring ?: synchronized(this) {
        val app = context.applicationContext
        ring ?: KeyRing(disk(app), File(app.filesDir, "keys"), Keystore, Argon2, cipher = { cipher(app) }).also {
            if (!it.exists) it.create()
            ring = it
        }
    }

    // XChaCha20 by default (decision 0013); AES stays a choice.
    fun cipher(context: Context): Cipher256 =
        try {
            Cipher256.of(Prefs(context).int(PREF_CIPHER, Cipher256.XCHACHA.id))
        } catch (e: IOException) {
            Cipher256.XCHACHA
        }

    fun wipeZeros(context: Context) = Prefs(context).bool(PREF_WIPE, false)

    // The device key of the time before the key ring is destroyed once neither the vault nor the secrets need it.
    @Synchronized
    fun legacyMoved(context: Context, part: String) {
        val prefs = Prefs(context)
        prefs.edit { putBoolean("legacyMoved:$part", true) }
        if (prefs.bool("legacyMoved:vault", false) && prefs.bool("legacyMoved:secrets", false)) Crypto.forget()
    }

    // auth: the key is let out only for a short time after the device unlock (a locked category, decision 0011); it
    // is lost for good when the screen lock is taken off.
    open class KeystoreKeys(private val auth: Boolean) : KeyRing.DeviceKeys {
        private fun store() = KeyStore.getInstance(STORE).apply { load(null) }

        private fun key(alias: String, create: Boolean): SecretKey {
            (store().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
            if (!create) throw IOException("no device key $alias")
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .apply {
                            if (auth) setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(false)
                                .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
                        }
                        .build(),
                )
                generateKey()
            }
        }

        override fun wrap(alias: String, plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key(alias, true))
            val body = c.doFinal(plain)
            return ByteBuffer.allocate(1 + c.iv.size + body.size).put(c.iv.size.toByte()).put(c.iv).put(body).array()
        }

        override fun unwrap(alias: String, blob: ByteArray): ByteArray {
            val input = ByteBuffer.wrap(blob)
            val iv = ByteArray(input.get().toInt()).also { input.get(it) }
            val body = ByteArray(input.remaining()).also { input.get(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(alias, false), GCMParameterSpec(128, iv))
            return c.doFinal(body)
        }

        override fun destroy(alias: String) = store().deleteEntry(alias)
    }

    object Keystore : KeystoreKeys(false)

    object AuthKeystore : KeystoreKeys(true)

    // The ring of a locked category: its one data key under the device unlock, a password of its own, or both.
    fun categoryRing(context: Context, name: String, dir: File) = KeyRing(disk(context), dir, AuthKeystore, Argon2,
        prefix = "antite-category-$name", cipher = { cipher(context) }, domains = listOf(CategoryKeys.DATA), first = listOf(CategoryKeys.DATA))

    // The derived key goes straight into the key core; the password is copied into guarded memory there and wiped.
    object Argon2 : KeyRing.Kdf {
        override fun derive(password: ByteArray, salt: ByteArray, params: KeyRing.KdfParams): CoreKey = CoreKey.derive(password, salt, params)
    }
}
