package dev.antite.keys

import org.json.JSONObject
import java.io.IOException
import java.util.Base64

// The password file of a key ring: the Argon2id parameters, the salt, and a box that tells a wrong password from a
// right one. The kdf must not keep the password; the caller wipes it.
internal object PasswordRecord {
    private val CHECK = "antite-password-check".toByteArray()

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    // A new record and the key it gives.
    fun make(kdf: KeyRing.Kdf, password: ByteArray, params: KeyRing.KdfParams, cipher: Cipher256): Pair<JSONObject, CoreKey> {
        val salt = Ciphers.randomBytes(16)
        val k = kdf.derive(password, salt, params)
        val o = JSONObject().put("salt", b64(salt)).put("t", params.iterations).put("m", params.memoryKib).put("p", params.parallelism)
        return resealed(o, k, cipher) to k
    }

    fun open(kdf: KeyRing.Kdf, password: ByteArray, o: JSONObject): CoreKey {
        val k = kdf.derive(password, unb64(o.getString("salt")), KeyRing.KdfParams(o.getInt("t"), o.getInt("m"), o.getInt("p")))
        try {
            Ciphers.unbox(k, unb64(o.getString("check")), CHECK).fill(0)
        } catch (e: IOException) {
            k.close()
            throw KeyRing.WrongPasswordException()
        }
        return k
    }

    fun cipherOf(o: JSONObject): Cipher256 = Ciphers.boxCipher(unb64(o.getString("check")))

    fun resealed(o: JSONObject, k: CoreKey, cipher: Cipher256): JSONObject = o.put("check", b64(Ciphers.box(cipher, k, CHECK, CHECK)))
}
