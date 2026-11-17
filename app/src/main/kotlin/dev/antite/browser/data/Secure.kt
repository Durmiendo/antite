package dev.antite.browser.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import dev.antite.keys.Cipher256
import dev.antite.keys.Disk
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
        ring ?: KeyRing(disk(context), File(context.applicationContext.filesDir, "keys"), Keystore, Argon2).also {
            if (!it.exists) it.create()
            ring = it
        }
    }

    fun cipher(context: Context): Cipher256 =
        runCatching { Cipher256.of(Prefs(context).int(PREF_CIPHER, Cipher256.AES.id)) }.getOrDefault(Cipher256.AES)

    fun wipeZeros(context: Context) = Prefs(context).bool(PREF_WIPE, false)

    // The device key of the time before the key ring is destroyed once neither the vault nor the secrets need it.
    @Synchronized
    fun legacyMoved(context: Context, part: String) {
        val prefs = Prefs(context)
        prefs.edit { putBoolean("legacyMoved:$part", true) }
        if (prefs.bool("legacyMoved:vault", false) && prefs.bool("legacyMoved:secrets", false)) Crypto.forget()
    }

    object Keystore : KeyRing.DeviceKeys {
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

    // The password goes in through a direct buffer that is zeroed here; the library keeps no copy of it.
    object Argon2 : KeyRing.Kdf {
        override fun derive(password: ByteArray, salt: ByteArray, params: KeyRing.KdfParams): ByteArray {
            val pw = ByteBuffer.allocateDirect(password.size).put(password).also { it.flip() }
            val s = ByteBuffer.allocateDirect(salt.size).put(salt).also { it.flip() }
            try {
                val r = Argon2Kt().hash(Argon2Mode.ARGON2_ID, pw, s, params.iterations, params.memoryKib, params.parallelism, Cipher256.KEY)
                return r.rawHashAsByteArray().also { r.rawHash.let { h -> h.clear(); while (h.hasRemaining()) h.put(0) } }
            } finally {
                pw.clear()
                while (pw.hasRemaining()) pw.put(0)
            }
        }
    }
}
