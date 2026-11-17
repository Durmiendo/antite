package dev.antite.browser.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64

// The data keys: one per domain (the vault, the saved secrets, the history), kept on the disk only wrapped
// by the device key, by a key from the password, or by both. Every rotation is a new generation; the device key of
// the old one is destroyed, which is what makes deleted data unrecoverable.
// prefix keeps Keystore aliases apart: tests run in the app's process and must never touch its real keys.
class KeyRing(private val dir: File, private val device: DeviceKeys, private val kdf: Kdf, private val prefix: String = "antite") {
    enum class Mode(val id: Int) {
        DEVICE(1), PASSWORD(2), BOTH(3);

        val usesPassword get() = this != DEVICE
        val usesDevice get() = this != PASSWORD

        companion object {
            fun of(id: Int) = entries.firstOrNull { it.id == id } ?: throw IOException("unknown key mode $id")
        }
    }

    // A device key per alias (Android Keystore on the phone).
    interface DeviceKeys {
        fun wrap(alias: String, plain: ByteArray): ByteArray
        fun unwrap(alias: String, blob: ByteArray): ByteArray
        fun destroy(alias: String)
    }

    // Password bytes and salt to a 64-byte key. Must not keep the password.
    fun interface Kdf {
        fun derive(password: ByteArray, salt: ByteArray, params: KdfParams): ByteArray
    }

    data class KdfParams(val iterations: Int, val memoryKib: Int, val parallelism: Int)

    class LockedException : IOException("the keys are locked")
    class WrongPasswordException : IOException("wrong password")

    companion object {
        const val VAULT = "vault"
        const val SECRETS = "secrets"
        const val HISTORY = "history"
        val DOMAINS = listOf(VAULT, SECRETS, HISTORY)
        // Made at first start; later domains are made on first use, under the mode of that moment.
        private val FIRST = listOf(VAULT, SECRETS)
        val DEFAULT_KDF = KdfParams(3, 64 * 1024, 2)
        private val CHECK = "antite-password-check".toByteArray()
    }

    private val keys = HashMap<String, ByteArray>()
    private var kek: ByteArray? = null

    init {
        dir.mkdirs()
    }

    private fun domainFile(domain: String) = File(dir, "$domain.json")
    private fun nextFile(domain: String) = File(dir, "$domain.next.json")
    private val passwordFile get() = File(dir, "password.json")

    private fun alias(domain: String, gen: Int) = "$prefix-$domain-$gen"

    private fun write(f: File, o: JSONObject) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) throw IOException("cannot replace ${f.name}")
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    private var knownMode: Mode? = null

    @get:Synchronized
    val mode: Mode
        get() = knownMode ?: (domainFile(VAULT).takeIf { it.exists() }?.let { Mode.of(JSONObject(it.readText()).getInt("mode")) }
            ?: Mode.DEVICE).also { knownMode = it }

    @get:Synchronized
    val exists get() = FIRST.all { domainFile(it).exists() }

    // Whether data keys can be had without a password right now.
    @get:Synchronized
    val unlocked get() = !mode.usesPassword || kek != null

    private fun seal(domain: String, gen: Int, m: Mode, key: ByteArray): JSONObject {
        var blob = key
        if (m.usesPassword) blob = Ciphers.box(Cipher256.AES, kek ?: throw LockedException(), blob, domain.toByteArray())
        if (m.usesDevice) blob = device.wrap(alias(domain, gen), blob)
        return JSONObject().put("mode", m.id).put("gen", gen).put("key", b64(blob))
    }

    private fun unseal(domain: String, o: JSONObject): ByteArray {
        val m = Mode.of(o.getInt("mode"))
        var blob = unb64(o.getString("key"))
        if (m.usesDevice) blob = device.unwrap(alias(domain, o.getInt("gen")), blob)
        if (m.usesPassword) {
            val inner = blob
            blob = Ciphers.unbox(kek ?: throw LockedException(), inner, domain.toByteArray())
            inner.fill(0)
        }
        return blob
    }

    // First start: new random data keys under the device key.
    @Synchronized
    fun create() {
        for (d in FIRST) if (!domainFile(d).exists()) {
            val k = Ciphers.randomBytes(Cipher256.KEY)
            write(domainFile(d), seal(d, 1, Mode.DEVICE, k))
            keys[d] = k
        }
    }

    // The data key of a domain. The caller must not keep it past use: lock() wipes the copy held here.
    @Synchronized
    fun key(domain: String): ByteArray {
        keys[domain]?.let { return it }
        if (mode.usesPassword && kek == null) throw LockedException()
        if (!domainFile(domain).exists()) {
            val k = Ciphers.randomBytes(Cipher256.KEY)
            write(domainFile(domain), seal(domain, 1, mode, k))
            return k.also { keys[domain] = it }
        }
        return unseal(domain, JSONObject(domainFile(domain).readText())).also { keys[domain] = it }
    }

    @Synchronized
    fun unlock(password: ByteArray) {
        try {
            val p = JSONObject(passwordFile.readText())
            val params = KdfParams(p.getInt("t"), p.getInt("m"), p.getInt("p"))
            val k = kdf.derive(password, unb64(p.getString("salt")), params)
            try {
                Ciphers.unbox(k, unb64(p.getString("check")), CHECK).fill(0)
            } catch (e: IOException) {
                k.fill(0)
                throw WrongPasswordException()
            }
            kek?.fill(0)
            kek = k
        } finally {
            password.fill(0)
        }
    }

    @Synchronized
    fun lock() {
        keys.values.forEach { it.fill(0) }
        keys.clear()
        kek?.fill(0)
        kek = null
    }

    // A new mode or password: the same data keys, wrapped again. A password, when given, is wiped here.
    @Synchronized
    fun setMode(m: Mode, password: ByteArray?, params: KdfParams = DEFAULT_KDF) {
        try {
            val plain = DOMAINS.associateWith { key(it).copyOf() }
            if (m.usesPassword) {
                val salt = Ciphers.randomBytes(16)
                val k = kdf.derive(password ?: throw WrongPasswordException(), salt, params)
                write(passwordFile, JSONObject().put("salt", b64(salt)).put("t", params.iterations).put("m", params.memoryKib)
                    .put("p", params.parallelism).put("check", b64(Ciphers.box(Cipher256.AES, k, CHECK, CHECK))))
                kek?.fill(0)
                kek = k
            }
            for ((d, k) in plain) {
                val old = JSONObject(domainFile(d).readText())
                val gen = old.getInt("gen") + 1
                write(domainFile(d), seal(d, gen, m, k))
                destroy(d, old)
                k.fill(0)
            }
            knownMode = m
            if (!m.usesPassword) {
                passwordFile.delete()
                kek?.fill(0)
                kek = null
            }
        } finally {
            password?.fill(0)
        }
    }

    private fun destroy(domain: String, o: JSONObject) {
        if (Mode.of(o.getInt("mode")).usesDevice) runCatching { device.destroy(alias(domain, o.getInt("gen"))) }
    }

    // Replaces the data key of a domain. rewrite() must re-encrypt everything under the new key and make it durable;
    // the new key is stored aside first, so a stop in the middle leaves both keys for recover().
    @Synchronized
    fun rotate(domain: String, rewrite: (old: ByteArray, new: ByteArray) -> Unit) {
        val old = key(domain)
        val current = JSONObject(domainFile(domain).readText())
        val m = Mode.of(current.getInt("mode"))
        val gen = current.getInt("gen") + 1
        val fresh = Ciphers.randomBytes(Cipher256.KEY)
        write(nextFile(domain), seal(domain, gen, m, fresh))
        rewrite(old, fresh)
        if (!nextFile(domain).renameTo(domainFile(domain))) throw IOException("cannot replace the $domain key")
        destroy(domain, current)
        old.fill(0)
        keys[domain] = fresh
    }

    // After a stop inside rotate(): works() tells whether the data is already under the new key.
    @Synchronized
    fun recover(domain: String, works: (ByteArray) -> Boolean) {
        val next = nextFile(domain).takeIf { it.exists() } ?: return
        val o = JSONObject(next.readText())
        val k = runCatching { unseal(domain, o) }.getOrNull()
        if (k != null && works(k)) {
            val current = JSONObject(domainFile(domain).readText())
            if (!next.renameTo(domainFile(domain))) throw IOException("cannot replace the $domain key")
            destroy(domain, current)
            keys.remove(domain)?.fill(0)
            keys[domain] = k
        } else {
            k?.fill(0)
            destroy(domain, o)
            next.delete()
        }
    }

    @get:Synchronized
    val recovering get() = DOMAINS.any { nextFile(it).exists() }

    // A data key from a backup replaces this domain's key under the current mode; the given array is wiped.
    @Synchronized
    fun install(domain: String, key: ByteArray) {
        try {
            if (!domainFile(domain).exists()) {
                write(domainFile(domain), seal(domain, 1, mode, key))
                return
            }
            val current = JSONObject(domainFile(domain).readText())
            val m = Mode.of(current.getInt("mode"))
            write(domainFile(domain), seal(domain, current.getInt("gen") + 1, m, key))
            destroy(domain, current)
            keys.remove(domain)?.fill(0)
        } finally {
            key.fill(0)
        }
    }
}
