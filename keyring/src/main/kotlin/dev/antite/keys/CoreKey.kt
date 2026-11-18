package dev.antite.keys

import java.io.Closeable

// A 64-byte key that lives only in the key core; Kotlin holds its handle. close() wipes it there, and a closed key
// cannot be used: the core refuses its handle even if a copy of the number survives.
class CoreKey private constructor(private var h: Long) : Closeable {
    companion object {
        fun random() = CoreKey(KeyCore.keyRandom())

        // A key from outside the core (the device key store, an old backup); raw is wiped.
        fun import(raw: ByteArray) = CoreKey(KeyCore.keyImport(raw))

        fun derive(password: ByteArray, salt: ByteArray, params: KeyRing.KdfParams) =
            CoreKey(KeyCore.keyDerive(password, salt, params.iterations, params.memoryKib, params.parallelism))

        // The key in a box made by wrap(), or null when the box does not open under wrapping.
        fun unwrap(wrapping: CoreKey, box: ByteArray, aad: ByteArray = ByteArray(0)): CoreKey? =
            KeyCore.unwrap(wrapping.handle, box, aad).takeIf { it != 0L }?.let(::CoreKey)
    }

    internal val handle: Long
        @Synchronized get() = h.takeIf { it != 0L } ?: throw IllegalStateException("the key is closed")

    val closed: Boolean @Synchronized get() = h == 0L

    // This key boxed under wrapping: what is stored or exported instead of the key.
    fun wrapUnder(wrapping: CoreKey, cipher: Cipher256, aad: ByteArray = ByteArray(0)): ByteArray =
        KeyCore.wrap(cipher.id, wrapping.handle, handle, aad)

    @Synchronized
    override fun close() {
        if (h == 0L) return
        KeyCore.keyFree(h)
        h = 0L
    }
}
