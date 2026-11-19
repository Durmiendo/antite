package dev.antite.keys

// A device key per alias (Android Keystore on the phone).
interface DeviceKeys {
    fun wrap(alias: String, plain: ByteArray): ByteArray
    fun unwrap(alias: String, blob: ByteArray): ByteArray
    fun destroy(alias: String)
}
