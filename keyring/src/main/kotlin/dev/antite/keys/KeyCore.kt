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

    // Null when the bytes do not open under this key or the arguments are wrong.
    @JvmStatic
    external fun seal(cipher: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, off: Int, len: Int, soft: Boolean): ByteArray?

    @JvmStatic
    external fun open(cipher: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, off: Int, len: Int, soft: Boolean): ByteArray?

    @JvmStatic
    external fun argon2id(password: ByteArray, salt: ByteArray, iterations: Int, memoryKib: Int, lanes: Int, length: Int): ByteArray?

    @JvmStatic
    external fun random(out: ByteArray)
}
