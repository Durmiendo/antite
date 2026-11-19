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
// is destroyed, which is what makes deleted data unrecoverable. prefix keeps Keystore aliases apart (tests run in the
// app's process; a locked category has its own ring); cipher: what new boxes are sealed with, boxes of any cipher open.
class KeyRing(
    private val disk: Disk,
    private val dir: File,
    private val device: DeviceKeys,
    private val kdf: Kdf,
    private val prefix: String = "antite",
    private val cipher: () -> Cipher256 = { Cipher256.XCHACHA },
    private val domains: List<KeyDomain> = DOMAINS,
    private val first: List<KeyDomain> = FIRST, // made at first start; the others on first use, under the mode of then
) {
    typealias Mode = KeyMode

    typealias DeviceKeys = dev.antite.keys.DeviceKeys

    // Password bytes and salt to a key in the core. Must not keep the password.
    fun interface Kdf {
        fun derive(password: ByteArray, salt: ByteArray, params: KdfParams): CoreKey
    }

    data class KdfParams(val iterations: Int, val memoryKib: Int, val parallelism: Int)

    class LockedException : IOException("the keys are locked")
    class WrongPasswordException : IOException("wrong password")

    companion object {
        val VAULT = KeyDomain("vault")
        val SECRETS = KeyDomain("secrets")
        val HISTORY = KeyDomain("history")
        val DOMAINS = listOf(VAULT, SECRETS, HISTORY)
        private val FIRST = listOf(VAULT, SECRETS)
        val DEFAULT_KDF = KdfParams(3, 64 * 1024, 2)
        private const val VERSION = 2
    }

    private val keys = HashMap<KeyDomain, CoreKey>()
    private var kek: CoreKey? = null

    private fun domainFile(domain: KeyDomain) = File(dir, "$domain.json")
    private fun nextFile(domain: KeyDomain) = File(dir, "$domain.next.json")
    private val passwordFile get() = File(dir, "password.json")
    // A change of mode or password is written aside whole, then decided by the commit file, then moved in.
    private fun stagedFile(domain: KeyDomain) = File(dir, "$domain.mode.json")
    private val stagedPassword get() = File(dir, "password.mode.json")
    private val commitFile get() = File(dir, "mode.commit")

    private fun alias(domain: KeyDomain, gen: Int) = "$prefix-$domain-$gen"

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
        get() = knownMode ?: (domainFile(first[0]).takeIf { disk.exists(it) }?.let { Mode.of(read(it).getInt("mode")) }
            ?: Mode.DEVICE).also { knownMode = it }

    @get:Synchronized
    val exists get() = first.all { disk.exists(domainFile(it)) }

    // Whether data keys can be had without a password right now.
    @get:Synchronized
    val unlocked get() = !mode.usesPassword || kek != null

    // The device's wrapping key of this domain and generation, made now: its bytes go to the device key store and
    // straight into the core.
    private fun newWrapping(domain: KeyDomain, gen: Int, o: JSONObject): CoreKey {
        val raw = Ciphers.randomBytes(Cipher256.KEY)
        o.put("w", b64(device.wrap(alias(domain, gen), raw)))
        return CoreKey.import(raw)
    }

    private fun wrapping(domain: KeyDomain, o: JSONObject): CoreKey =
        CoreKey.import(device.unwrap(alias(domain, o.getInt("gen")), unb64(o.getString("w"))))

    private fun seal(domain: KeyDomain, gen: Int, m: Mode, key: CoreKey, with: CoreKey? = kek): JSONObject {
        val aad = domain.name.toByteArray()
        val o = JSONObject().put("v", VERSION).put("mode", m.id).put("gen", gen)
        val byPassword = if (m.usesPassword) key.wrapUnder(with ?: throw LockedException(), cipher(), aad) else null
        val box = if (m.usesDevice) {
            newWrapping(domain, gen, o).use { w -> if (byPassword != null) Ciphers.box(cipher(), w, byPassword, aad) else key.wrapUnder(w, cipher(), aad) }
        } else byPassword!!
        return o.put("key", b64(box))
    }

    private fun unseal(domain: KeyDomain, o: JSONObject): CoreKey {
        val aad = domain.name.toByteArray()
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
    private fun unsealV1(domain: KeyDomain, o: JSONObject, m: Mode, blob: ByteArray): CoreKey {
        val inner = if (m.usesDevice) device.unwrap(alias(domain, o.getInt("gen")), blob) else blob
        if (!m.usesPassword) return CoreKey.import(inner)
        return CoreKey.unwrap(kek ?: throw LockedException(), inner, domain.name.toByteArray()) ?: throw IOException("$domain key damaged")
    }

    // First start: the first domains under mode m, with new random data keys or those keyOf gives (the ring takes
    // them). A password, when given, is wiped here.
    @Synchronized
    fun create(m: Mode = Mode.DEVICE, password: ByteArray? = null, keyOf: (KeyDomain) -> CoreKey = { CoreKey.random() }) {
        try {
            if (m.usesPassword) {
                val (record, k) = PasswordRecord.make(kdf, password ?: throw WrongPasswordException(), DEFAULT_KDF, cipher())
                write(passwordFile, record)
                kek?.close()
                kek = k
            }
            for (d in first) if (!disk.exists(domainFile(d))) {
                val k = keyOf(d)
                write(domainFile(d), seal(d, 1, m, k))
                keys[d] = k
            }
            knownMode = m
        } finally {
            password?.fill(0)
        }
    }

    // The data key of a domain, held by the ring: the caller must not close it nor keep it past use, lock() closes it.
    @Synchronized
    fun key(domain: KeyDomain): CoreKey {
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
    fun <R> withKey(domain: KeyDomain, block: (CoreKey) -> R): R = block(key(domain))

    @Synchronized
    fun unlock(password: ByteArray) {
        try {
            val k = PasswordRecord.open(kdf, password, read(passwordFile))
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
            val held = domains.associateWith { key(it) }
            if (m.usesPassword) {
                val (record, k) = PasswordRecord.make(kdf, password ?: throw WrongPasswordException(), params, cipher())
                next = k
                write(stagedPassword, record)
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
    private fun passwordBoxCipher(domain: KeyDomain, o: JSONObject): Cipher256? {
        val m = Mode.of(o.getInt("mode"))
        if (!m.usesPassword) return null
        val blob = unb64(o.getString("key"))
        if (o.optInt("v", 1) == 1) return Ciphers.boxCipher(if (m.usesDevice) device.unwrap(alias(domain, o.getInt("gen")), blob) else blob)
        if (!m.usesDevice) return Ciphers.boxCipher(blob)
        return wrapping(domain, o).use { w -> Ciphers.boxCipher(Ciphers.unbox(w, blob, domain.name.toByteArray())) }
    }

    // Whether a file of the ring is of the old version or has a box sealed with another cipher than the current one.
    @Synchronized
    fun needsRewrap(): Boolean {
        val target = cipher()
        if (mode.usesPassword && PasswordRecord.cipherOf(read(passwordFile)) != target) return true
        return domains.any { d ->
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
        for (d in domains) {
            val f = domainFile(d)
            if (!disk.exists(f)) continue
            val o = read(f)
            write(f, seal(d, o.getInt("gen"), Mode.of(o.getInt("mode")), key(d)))
        }
        val k = kek ?: return
        write(passwordFile, PasswordRecord.resealed(read(passwordFile), k, cipher()))
    }

    // A change of mode cut short: without the commit file it is undone, with it it is carried through.
    private fun finishModeChange() {
        val staged = domains.filter { disk.exists(stagedFile(it)) }
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

    private fun destroy(domain: KeyDomain, o: JSONObject) {
        if (Mode.of(o.getInt("mode")).usesDevice) orNull { device.destroy(alias(domain, o.getInt("gen"))) }
    }

    // Replaces the data key of a domain. rewrite() must re-encrypt everything under the new key and make it durable;
    // the new key is stored aside first, so a stop in the middle leaves both keys for recover().
    @Synchronized
    fun rotate(domain: KeyDomain, rewrite: (old: CoreKey, new: CoreKey) -> Unit) {
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
    fun recover(domain: KeyDomain, works: (CoreKey) -> Boolean) {
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

    // The whole ring goes: the device keys of every file of it first, then the files. What it held cannot be opened
    // again, even from a copy of the files.
    @Synchronized
    fun destroy() {
        for (d in domains) for (f in listOf(domainFile(d), nextFile(d), stagedFile(d), importFile(d))) {
            if (disk.exists(f)) orNull { destroy(d, read(f)) }
        }
        lock()
        disk.deleteTree(dir)
    }

    @get:Synchronized
    val recovering get() = domains.any { disk.exists(nextFile(it)) }

    private fun importFile(domain: KeyDomain) = File(dir, "$domain.import.json")

    // A data key from a backup, sealed under the current mode next to this domain's key; adoptImports() puts it in
    // place, dropImports() forgets it. The given key is closed.
    @Synchronized
    fun stageImport(domain: KeyDomain, key: CoreKey) {
        key.use {
            val gen = if (disk.exists(domainFile(domain))) read(domainFile(domain)).getInt("gen") + 1 else 1
            write(importFile(domain), seal(domain, gen, mode, it))
        }
    }

    // Can be run again after a stop: the old device key goes first, the staged file stays until it is moved in.
    @Synchronized
    fun adoptImports() {
        for (d in domains) {
            val f = importFile(d)
            if (!disk.exists(f)) continue
            if (disk.exists(domainFile(d))) destroy(d, read(domainFile(d)))
            disk.renameDurably(f, domainFile(d))
            keys.remove(d)?.close()
        }
    }

    @Synchronized
    fun dropImports() {
        for (d in domains) {
            val f = importFile(d)
            if (!disk.exists(f)) continue
            orNull { destroy(d, read(f)) }
            disk.deleteDurably(f)
        }
    }

    // A data key from a backup replaces this domain's key under the current mode; the given key is closed.
    @Synchronized
    fun install(domain: KeyDomain, key: CoreKey) {
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
