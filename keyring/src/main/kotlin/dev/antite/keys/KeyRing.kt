package dev.antite.keys

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64

// The data keys: one per domain (the vault, the saved secrets, the history). They live in the key core; the disk
// holds them only boxed, under a key from the password, under a wrapping key of the device, or both. The device key
// store wraps that wrapping key, never a data key. Every rotation is a new generation; the device key of the old one
// is destroyed, which is what makes deleted data unrecoverable.
// prefix keeps Keystore aliases apart: tests run in the app's process and must never touch its real keys.
// cipher: what new boxes are sealed with; boxes of any cipher open.
class KeyRing(
    private val disk: Disk,
    private val dir: File,
    private val device: DeviceKeys,
    private val kdf: Kdf,
    private val prefix: String = "antite",
    private val cipher: () -> Cipher256 = { Cipher256.XCHACHA },
) {
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

    // Password bytes and salt to a key in the core. Must not keep the password.
    fun interface Kdf {
        fun derive(password: ByteArray, salt: ByteArray, params: KdfParams): CoreKey
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
        private const val VERSION = 2
    }

    private val keys = HashMap<String, CoreKey>()
    private var kek: CoreKey? = null

    private fun domainFile(domain: String) = File(dir, "$domain.json")
    private fun nextFile(domain: String) = File(dir, "$domain.next.json")
    private val passwordFile get() = File(dir, "password.json")
    // A change of mode or password is written aside whole, then decided by the commit file, then moved in.
    private fun stagedFile(domain: String) = File(dir, "$domain.mode.json")
    private val stagedPassword get() = File(dir, "password.mode.json")
    private val commitFile get() = File(dir, "mode.commit")

    private fun alias(domain: String, gen: Int) = "$prefix-$domain-$gen"

    private fun write(f: File, o: JSONObject) = disk.replace(f, o.toString().toByteArray())
    private fun read(f: File) = JSONObject(String(disk.readAll(f)))

    init {
        disk.mkdirs(dir)
        finishModeChange()
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    private var knownMode: Mode? = null

    @get:Synchronized
    val mode: Mode
        get() = knownMode ?: (domainFile(VAULT).takeIf { disk.exists(it) }?.let { Mode.of(read(it).getInt("mode")) }
            ?: Mode.DEVICE).also { knownMode = it }

    @get:Synchronized
    val exists get() = FIRST.all { disk.exists(domainFile(it)) }

    // Whether data keys can be had without a password right now.
    @get:Synchronized
    val unlocked get() = !mode.usesPassword || kek != null

    // The device's wrapping key of this domain and generation, made now: its bytes go to the device key store and
    // straight into the core.
    private fun newWrapping(domain: String, gen: Int, o: JSONObject): CoreKey {
        val raw = Ciphers.randomBytes(Cipher256.KEY)
        o.put("w", b64(device.wrap(alias(domain, gen), raw)))
        return CoreKey.import(raw)
    }

    private fun wrapping(domain: String, o: JSONObject): CoreKey =
        CoreKey.import(device.unwrap(alias(domain, o.getInt("gen")), unb64(o.getString("w"))))

    private fun seal(domain: String, gen: Int, m: Mode, key: CoreKey, with: CoreKey? = kek): JSONObject {
        val aad = domain.toByteArray()
        val o = JSONObject().put("v", VERSION).put("mode", m.id).put("gen", gen)
        val byPassword = if (m.usesPassword) key.wrapUnder(with ?: throw LockedException(), cipher(), aad) else null
        val box = if (m.usesDevice) {
            newWrapping(domain, gen, o).use { w -> if (byPassword != null) Ciphers.box(cipher(), w, byPassword, aad) else key.wrapUnder(w, cipher(), aad) }
        } else byPassword!!
        return o.put("key", b64(box))
    }

    private fun unseal(domain: String, o: JSONObject): CoreKey {
        val aad = domain.toByteArray()
        val m = Mode.of(o.getInt("mode"))
        val blob = unb64(o.getString("key"))
        if (o.optInt("v", 1) == 1) return unsealV1(domain, o, m, blob)
        val byPassword = if (m.usesDevice) {
            wrapping(domain, o).use { w ->
                if (!m.usesPassword) return CoreKey.unwrap(w, blob, aad) ?: throw IOException("$domain key damaged")
                Ciphers.unbox(w, blob, aad)
            }
        } else blob
        return CoreKey.unwrap(kek ?: throw LockedException(), byPassword, aad) ?: throw IOException("$domain key damaged")
    }

    // Version 1: the device key store wrapped the data key itself (or its password box). Read once, then written again.
    private fun unsealV1(domain: String, o: JSONObject, m: Mode, blob: ByteArray): CoreKey {
        val inner = if (m.usesDevice) device.unwrap(alias(domain, o.getInt("gen")), blob) else blob
        if (!m.usesPassword) return CoreKey.import(inner)
        return CoreKey.unwrap(kek ?: throw LockedException(), inner, domain.toByteArray()) ?: throw IOException("$domain key damaged")
    }

    // First start: new random data keys under the device key.
    @Synchronized
    fun create() {
        for (d in FIRST) if (!disk.exists(domainFile(d))) {
            val k = CoreKey.random()
            write(domainFile(d), seal(d, 1, Mode.DEVICE, k))
            keys[d] = k
        }
    }

    // The data key of a domain, held by the ring: the caller must not close it nor keep it past use, lock() closes it.
    @Synchronized
    fun key(domain: String): CoreKey {
        keys[domain]?.let { return it }
        if (mode.usesPassword && kek == null) throw LockedException()
        val f = domainFile(domain)
        if (!disk.exists(f)) {
            val k = CoreKey.random()
            write(f, seal(domain, 1, mode, k))
            return k.also { keys[domain] = it }
        }
        val o = read(f)
        val k = unseal(domain, o)
        // A file of version 1 is written again at once: the same key, the same generation, boxed in the core.
        if (o.optInt("v", 1) < VERSION) write(f, seal(domain, o.getInt("gen"), Mode.of(o.getInt("mode")), k))
        return k.also { keys[domain] = it }
    }

    // The domain's key for the length of block: lock() waits for it, so a key is never closed while it is in use.
    @Synchronized
    fun <R> withKey(domain: String, block: (CoreKey) -> R): R = block(key(domain))

    @Synchronized
    fun unlock(password: ByteArray) {
        try {
            val p = read(passwordFile)
            val params = KdfParams(p.getInt("t"), p.getInt("m"), p.getInt("p"))
            val k = kdf.derive(password, unb64(p.getString("salt")), params)
            try {
                Ciphers.unbox(k, unb64(p.getString("check")), CHECK).fill(0)
            } catch (e: IOException) {
                k.close()
                throw WrongPasswordException()
            }
            kek?.close()
            kek = k
        } finally {
            password.fill(0)
        }
    }

    @Synchronized
    fun lock() {
        keys.values.forEach { it.close() }
        keys.clear()
        kek?.close()
        kek = null
    }

    // A new mode or password: the same data keys, wrapped again. A password, when given, is wiped here. A stop anywhere
    // leaves the old mode and password or the new ones, never a mix: until the commit file is on the disk the old files
    // stand; after it, the next start moves the rest in.
    @Synchronized
    fun setMode(m: Mode, password: ByteArray?, params: KdfParams = DEFAULT_KDF) {
        var next: CoreKey? = null
        try {
            val held = DOMAINS.associateWith { key(it) }
            if (m.usesPassword) {
                val salt = Ciphers.randomBytes(16)
                val k = kdf.derive(password ?: throw WrongPasswordException(), salt, params)
                next = k
                write(stagedPassword, JSONObject().put("salt", b64(salt)).put("t", params.iterations).put("m", params.memoryKib)
                    .put("p", params.parallelism).put("check", b64(Ciphers.box(cipher(), k, CHECK, CHECK))))
            }
            val old = ArrayList<String>()
            for ((d, k) in held) {
                val current = read(domainFile(d))
                if (Mode.of(current.getInt("mode")).usesDevice) old += alias(d, current.getInt("gen"))
                write(stagedFile(d), seal(d, current.getInt("gen") + 1, m, k, next))
            }
            write(commitFile, JSONObject().put("destroy", JSONArray(old)))
            finishModeChange()
            kek?.close()
            kek = next
            next = null
            knownMode = m
        } finally {
            next?.close()
            password?.fill(0)
        }
    }

    // The cipher of the box that holds a domain's key under the password (the inner one when the device wraps it too).
    private fun passwordBoxCipher(domain: String, o: JSONObject): Cipher256? {
        val m = Mode.of(o.getInt("mode"))
        if (!m.usesPassword) return null
        val blob = unb64(o.getString("key"))
        if (o.optInt("v", 1) == 1) return Ciphers.boxCipher(if (m.usesDevice) device.unwrap(alias(domain, o.getInt("gen")), blob) else blob)
        if (!m.usesDevice) return Ciphers.boxCipher(blob)
        return wrapping(domain, o).use { w -> Ciphers.boxCipher(Ciphers.unbox(w, blob, domain.toByteArray())) }
    }

    // Whether a file of the ring is of the old version or has a box sealed with another cipher than the current one.
    @Synchronized
    fun needsRewrap(): Boolean {
        val target = cipher()
        if (mode.usesPassword && Ciphers.boxCipher(unb64(read(passwordFile).getString("check"))) != target) return true
        return DOMAINS.any { d ->
            val f = domainFile(d)
            if (!disk.exists(f)) return@any false
            val o = read(f)
            if (o.optInt("v", 1) < VERSION) return@any true
            val blob = unb64(o.getString("key"))
            (passwordBoxCipher(d, o) ?: target) != target || (Mode.of(o.getInt("mode")).usesDevice && Ciphers.boxCipher(blob) != target)
        }
    }

    // Every file of the ring sealed again with the current cipher: the same keys, the same generation, so the same
    // device key. Each file is replaced whole and both the old and the new one open, so a stop anywhere leaves a ring
    // that opens. With a password it needs the ring unlocked.
    @Synchronized
    fun rewrap() {
        if (mode.usesPassword && kek == null) throw LockedException()
        for (d in DOMAINS) {
            val f = domainFile(d)
            if (!disk.exists(f)) continue
            val o = read(f)
            write(f, seal(d, o.getInt("gen"), Mode.of(o.getInt("mode")), key(d)))
        }
        val k = kek ?: return
        val p = read(passwordFile)
        write(passwordFile, p.put("check", b64(Ciphers.box(cipher(), k, CHECK, CHECK))))
    }

    // A change of mode cut short: without the commit file it is undone, with it it is carried through.
    private fun finishModeChange() {
        val staged = DOMAINS.filter { disk.exists(stagedFile(it)) }
        if (!disk.exists(commitFile)) {
            for (d in staged) {
                orNull { destroy(d, read(stagedFile(d))) }
                disk.deleteDurably(stagedFile(d))
            }
            disk.deleteDurably(stagedPassword)
            return
        }
        if (disk.exists(stagedPassword)) disk.renameDurably(stagedPassword, passwordFile)
        for (d in staged) disk.renameDurably(stagedFile(d), domainFile(d))
        knownMode = null
        if (!mode.usesPassword) disk.deleteDurably(passwordFile)
        val commit = read(commitFile)
        val old = commit.getJSONArray("destroy")
        for (i in 0 until old.length()) orNull { device.destroy(old.getString(i)) }
        disk.deleteDurably(commitFile)
    }

    // A damaged file or a Keystore refusal; a crash or a lack of memory is not one and goes on up.
    private inline fun <T> orNull(block: () -> T): T? = try { block() } catch (e: IOException) { null } catch (e: java.security.GeneralSecurityException) { null }
        catch (e: JSONException) { null } catch (e: IllegalArgumentException) { null }

    private fun destroy(domain: String, o: JSONObject) {
        if (Mode.of(o.getInt("mode")).usesDevice) orNull { device.destroy(alias(domain, o.getInt("gen"))) }
    }

    // Replaces the data key of a domain. rewrite() must re-encrypt everything under the new key and make it durable;
    // the new key is stored aside first, so a stop in the middle leaves both keys for recover().
    @Synchronized
    fun rotate(domain: String, rewrite: (old: CoreKey, new: CoreKey) -> Unit) {
        val old = key(domain)
        val current = read(domainFile(domain))
        val m = Mode.of(current.getInt("mode"))
        val gen = current.getInt("gen") + 1
        val fresh = CoreKey.random()
        try {
            write(nextFile(domain), seal(domain, gen, m, fresh))
            rewrite(old, fresh)
            disk.renameDurably(nextFile(domain), domainFile(domain))
        } catch (e: Throwable) {
            fresh.close()
            throw e
        }
        destroy(domain, current)
        old.close()
        keys[domain] = fresh
    }

    // After a stop inside rotate(): works() tells whether the data is already under the new key.
    @Synchronized
    fun recover(domain: String, works: (CoreKey) -> Boolean) {
        val next = nextFile(domain).takeIf { disk.exists(it) } ?: return
        val o = read(next)
        val k = orNull { unseal(domain, o) }
        if (k != null && works(k)) {
            val current = read(domainFile(domain))
            disk.renameDurably(next, domainFile(domain))
            destroy(domain, current)
            keys.remove(domain)?.close()
            keys[domain] = k
        } else {
            k?.close()
            destroy(domain, o)
            disk.deleteDurably(next)
        }
    }

    @get:Synchronized
    val recovering get() = DOMAINS.any { disk.exists(nextFile(it)) }

    private fun importFile(domain: String) = File(dir, "$domain.import.json")

    // A data key from a backup, sealed under the current mode next to this domain's key; adoptImports() puts it in
    // place, dropImports() forgets it. The given key is closed.
    @Synchronized
    fun stageImport(domain: String, key: CoreKey) {
        key.use {
            val gen = if (disk.exists(domainFile(domain))) read(domainFile(domain)).getInt("gen") + 1 else 1
            write(importFile(domain), seal(domain, gen, mode, it))
        }
    }

    // Can be run again after a stop: the old device key goes first, the staged file stays until it is moved in.
    @Synchronized
    fun adoptImports() {
        for (d in DOMAINS) {
            val f = importFile(d)
            if (!disk.exists(f)) continue
            if (disk.exists(domainFile(d))) destroy(d, read(domainFile(d)))
            disk.renameDurably(f, domainFile(d))
            keys.remove(d)?.close()
        }
    }

    @Synchronized
    fun dropImports() {
        for (d in DOMAINS) {
            val f = importFile(d)
            if (!disk.exists(f)) continue
            orNull { destroy(d, read(f)) }
            disk.deleteDurably(f)
        }
    }

    // A data key from a backup replaces this domain's key under the current mode; the given key is closed.
    @Synchronized
    fun install(domain: String, key: CoreKey) {
        key.use {
            if (!disk.exists(domainFile(domain))) {
                write(domainFile(domain), seal(domain, 1, mode, it))
                return
            }
            val current = read(domainFile(domain))
            val m = Mode.of(current.getInt("mode"))
            write(domainFile(domain), seal(domain, current.getInt("gen") + 1, m, it))
            destroy(domain, current)
            keys.remove(domain)?.close()
        }
    }
}
