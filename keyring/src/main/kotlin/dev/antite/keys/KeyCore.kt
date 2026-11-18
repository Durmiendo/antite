package dev.antite.keys

// The native key core (keycore/). On the phone it is the app's libkeycore.so; the JVM tests point the antite.keycore
// property at the host build. soft = true takes the software AES even where the processor has AES instructions.
object KeyCore {
    init {
        val path = System.getProperty("antite.keycore")
        if (path != null) System.load(path) else System.loadLibrary("keycore")
        check(init()) { "the key core did not start" }
    }

    @JvmStatic
    private external fun init(): Boolean

    @JvmStatic
    external fun aesHardware(): Boolean

    // Whether the pages holding keys are locked in memory, so never written to swap. False when the system refused.
    @JvmStatic
    external fun memoryLocked(): Boolean

    // Every call throws IllegalArgumentException on wrong arguments and OutOfMemoryError when the core has no memory;
    // open gives null only when the bytes do not open under this key.
    @JvmStatic
    external fun seal(cipher: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, off: Int, len: Int, soft: Boolean): ByteArray

    @JvmStatic
    external fun open(cipher: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, off: Int, len: Int, soft: Boolean): ByteArray?

    @JvmStatic
    external fun argon2id(password: ByteArray, salt: ByteArray, iterations: Int, memoryKib: Int, lanes: Int, length: Int): ByteArray

    @JvmStatic
    external fun random(out: ByteArray)

    // The key table: keys live in the core, Kotlin holds handles (CoreKey). A handle of a freed key is refused with
    // IllegalArgumentException.
    @JvmStatic
    external fun keyRandom(): Long

    // The array is wiped.
    @JvmStatic
    external fun keyImport(raw: ByteArray): Long

    @JvmStatic
    external fun keyDerive(password: ByteArray, salt: ByteArray, iterations: Int, memoryKib: Int, lanes: Int): Long

    @JvmStatic
    external fun keyFree(key: Long)

    @JvmStatic
    external fun keyCount(): Long

    @JvmStatic
    external fun sealWith(cipher: Int, key: Long, nonce: ByteArray, aad: ByteArray, data: ByteArray, off: Int, len: Int, soft: Boolean): ByteArray

    @JvmStatic
    external fun openWith(cipher: Int, key: Long, nonce: ByteArray, aad: ByteArray, data: ByteArray, off: Int, len: Int, soft: Boolean): ByteArray?

    // A box of key under wrapping (cipher id, nonce, sealed key): the only form in which a key leaves the core.
    @JvmStatic
    external fun wrap(cipher: Int, wrapping: Long, key: Long, aad: ByteArray): ByteArray

    // The key in a box made by wrap, put into the table; 0 when it does not open under wrapping.
    @JvmStatic
    external fun unwrap(wrapping: Long, box: ByteArray, aad: ByteArray): Long
}
