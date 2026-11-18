package dev.antite.keys

import java.security.MessageDigest

// Keys never leave the core, so tests tell them apart by what they seal: a fixed text under a fixed nonce gives the
// same bytes for the same key and different ones for another.
fun CoreKey.print(): ByteArray = Ciphers.seal(Cipher256.XCHACHA, this, ByteArray(24), "key print".toByteArray(), ByteArray(16))

fun CoreKey.sameAs(other: CoreKey) = print().contentEquals(other.print())

// A key in the core from these bytes; the array given stays as it was.
fun keyOf(raw: ByteArray) = CoreKey.import(raw.copyOf())

// A fast stand-in for Argon2id in tests: SHA-512 of the password and salt (and the parameters, when asked).
fun fastKdf(withParams: Boolean = false) = KeyRing.Kdf { password, salt, params ->
    CoreKey.import(MessageDigest.getInstance("SHA-512").digest(password + salt + if (withParams) params.toString().toByteArray() else ByteArray(0)))
}

// Tests with fixed keys (golden files, published vectors) hand the raw bytes to the core for one call.
fun sealRaw(cipher: Cipher256, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, soft: Boolean = false) =
    keyOf(key).use { Ciphers.seal(cipher, it, nonce, aad, data, soft = soft) }

fun openRaw(cipher: Cipher256, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, soft: Boolean = false): ByteArray? =
    keyOf(key).use { k ->
        try {
            Ciphers.open(cipher, k, nonce, aad, data, soft = soft)
        } catch (e: java.io.IOException) {
            null
        }
    }

fun boxRaw(cipher: Cipher256, key: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)) = keyOf(key).use { Ciphers.box(cipher, it, plain, aad) }

fun unboxRaw(key: ByteArray, boxed: ByteArray, aad: ByteArray = ByteArray(0)) = keyOf(key).use { Ciphers.unbox(it, boxed, aad) }
