package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

// Every operation of every store, cut short before each of its steps on the disk, by a kill, a power cut and a torn
// write: what comes back is the old state or the new one, never damaged and never lost.
class CrashTest {
    private val dir = File("/data/files/keys")
    private val kdf = fastKdf()
    private fun pw(s: String) = s.toByteArray()

    private class Ring(val device: CrashDevice, val keys: Map<String, ByteArray>)

    private fun ring(d: CrashDisk, device: CrashDevice) = KeyRing(d, dir, device, kdf)

    private fun ringIn(d: CrashDisk, mode: KeyRing.Mode, password: String?): Ring {
        val device = CrashDevice(d)
        val r = ring(d, device).apply { create() }
        val keys = KeyRing.DOMAINS.associateWith { r.key(it).print() }
        if (mode != KeyRing.Mode.DEVICE) r.setMode(mode, pw(password!!))
        return Ring(device, keys)
    }

    // The ring after a restart, opened with whichever password its mode asks for.
    private fun reopen(d: CrashDisk, s: Ring, passwords: Map<KeyRing.Mode, String?>, where: String): KeyRing {
        val r = ring(d, s.device)
        val p = passwords[r.mode] ?: if (r.mode.usesPassword) error("$where: mode ${r.mode} was never asked for") else null
        if (p != null) r.unlock(pw(p))
        return r
    }

    private fun assertKeys(r: KeyRing, keys: Map<String, ByteArray>, where: String) {
        for ((d, k) in keys) assertArrayEquals("$where: $d", k, r.key(d).print())
    }

    @Test
    fun firstStart() = Crashes.everywhere(
        setup = { d -> CrashDevice(d) },
        action = { d, device -> ring(d, device).create() },
        check = { d, device, finished, where ->
            val r = ring(d, device)
            if (finished) assertTrue(where, r.exists)
            r.create()
            assertTrue(where, r.exists)
            KeyRing.DOMAINS.forEach { r.key(it) }
            assertNoLeftovers(d, where)
        },
    )

    private fun modeChange(from: KeyRing.Mode, fromPw: String?, to: KeyRing.Mode, toPw: String?) = Crashes.everywhere(
        setup = { d -> ringIn(d, from, fromPw) },
        action = { d, s -> reopen(d, s, mapOf(from to fromPw), "setup").setMode(to, toPw?.let(::pw)) },
        check = { d, s, finished, where ->
            val r = ring(d, s.device)
            if (finished) assertEquals(where, to, r.mode)
            val mode = r.mode
            val p = when {
                mode == to && (from != to || finished) -> toPw
                mode == from -> fromPw
                else -> null
            }
            if (mode != from && mode != to) fail("$where: mode $mode")
            if (mode.usesPassword) {
                // A password change keeps the mode: whichever of the two passwords opens it.
                val ok = listOfNotNull(p, fromPw, toPw).distinct().any { runCatching { r.unlock(pw(it)) }.isSuccess }
                assertTrue("$where: no password opens the ring", ok)
            }
            assertKeys(r, s.keys, where)
            assertNoLeftovers(d, where)
            if (finished && fromPw != toPw && to.usesPassword) {
                val again = ring(d, s.device)
                runCatching { again.unlock(pw(fromPw ?: "")) }.onSuccess { fail("$where: the old password still opens it") }
            }
        },
    )

    @Test
    fun deviceToPassword() = modeChange(KeyRing.Mode.DEVICE, null, KeyRing.Mode.PASSWORD, "new one")

    @Test
    fun passwordChange() = modeChange(KeyRing.Mode.PASSWORD, "old one", KeyRing.Mode.PASSWORD, "new one")

    @Test
    fun passwordToBoth() = modeChange(KeyRing.Mode.PASSWORD, "old one", KeyRing.Mode.BOTH, "new one")

    @Test
    fun bothToDevice() = modeChange(KeyRing.Mode.BOTH, "old one", KeyRing.Mode.DEVICE, null)

    private val data = File("/data/files/vault/index.box")
    private val payload = "the index".toByteArray()

    @Test
    fun rotation() = Crashes.everywhere(
        setup = { d ->
            val s = ringIn(d, KeyRing.Mode.DEVICE, null)
            d.mkdirs(data.parentFile)
            d.replace(data, Ciphers.box(Cipher256.XCHACHA, ring(d, s.device).key(KeyRing.VAULT), payload))
            s
        },
        action = { d, s -> ring(d, s.device).rotate(KeyRing.VAULT) { _, fresh -> d.replace(data, Ciphers.box(Cipher256.XCHACHA, fresh, payload)) } },
        check = { d, s, finished, where ->
            val r = ring(d, s.device)
            val opens = { k: CoreKey -> runCatching { Ciphers.unbox(k, d.readAll(data)) }.isSuccess }
            r.recover(KeyRing.VAULT, opens)
            assertFalse(where, r.recovering)
            val k = r.key(KeyRing.VAULT)
            assertArrayEquals(where, payload, Ciphers.unbox(k, d.readAll(data)))
            if (finished) assertFalse(where, k.print().contentEquals(s.keys.getValue(KeyRing.VAULT)))
            assertNoLeftovers(d, where)
        },
    )

    @Test
    fun install() {
        val incomingRaw = Ciphers.randomBytes(Cipher256.KEY)
        val incoming = keyOf(incomingRaw).use { it.print() }
        Crashes.everywhere(
            setup = { d -> ringIn(d, KeyRing.Mode.DEVICE, null) },
            action = { d, s -> ring(d, s.device).install(KeyRing.SECRETS, keyOf(incomingRaw)) },
            check = { d, s, finished, where ->
                val k = ring(d, s.device).key(KeyRing.SECRETS).print()
                if (finished) assertArrayEquals(where, incoming, k)
                else assertTrue(where, k.contentEquals(incoming) || k.contentEquals(s.keys.getValue(KeyRing.SECRETS)))
            },
        )
    }

    private val history = File("/data/files/history")
    private val historyKeys = File(history, "keys")
    private val domain = keyOf(ByteArray(Cipher256.KEY) { it.toByte() })

    private fun categoryKeys(d: CrashDisk) = CategoryKeys(d, historyKeys, { op -> op(domain) }) { Cipher256.XCHACHA }
    private fun log(d: CrashDisk, key: CoreKey) = EncryptedLog(d, File(history, "a.log"), key) { Cipher256.XCHACHA }
    private fun texts(l: EncryptedLog) = l.read().map { String(it) }

    @Test
    fun aNewCategoryAndItsFirstRecord() = Crashes.everywhere(
        setup = { },
        action = { d, _ ->
            log(d, categoryKeys(d).key("a")).apply { read(); append("r0".toByteArray()) }
        },
        check = { d, _, finished, where ->
            val got = texts(log(d, categoryKeys(d).key("a")))
            if (finished) assertEquals(where, listOf("r0"), got) else assertTrue("$where: $got", got.isEmpty() || got == listOf("r0"))
        },
    )

    private fun withLog(records: List<String>): (CrashDisk) -> CoreKey = { d ->
        val k = categoryKeys(d).key("a")
        log(d, k).apply { read(); appendAll(records.map { it.toByteArray() }) }
        k
    }

    @Test
    fun appends() = Crashes.everywhere(
        setup = withLog(listOf("r0", "r1")),
        action = { d, k -> log(d, k).apply { read(); appendAll(listOf("r2".toByteArray(), "r3".toByteArray())) } },
        check = { d, k, finished, where ->
            val got = texts(log(d, k))
            val all = listOf("r0", "r1", "r2", "r3")
            if (finished) assertEquals(where, all, got)
            else assertTrue("$where: $got", got.size >= 2 && got == all.take(got.size))
        },
    )

    @Test
    fun compactionThenAppend() = Crashes.everywhere(
        setup = withLog(listOf("r0", "r1", "r2", "r3")),
        action = { d, k -> log(d, k).apply { read(); rewrite(listOf("r1".toByteArray(), "r3".toByteArray())); append("r4".toByteArray()) } },
        check = { d, k, finished, where ->
            val got = texts(log(d, k))
            val ok = listOf(listOf("r0", "r1", "r2", "r3"), listOf("r1", "r3"), listOf("r1", "r3", "r4"))
            if (finished) assertEquals(where, ok.last(), got) else assertTrue("$where: $got", got in ok)
            assertNoLeftovers(d, where)
        },
    )

    @Test
    fun forgettingACategory() = Crashes.everywhere(
        setup = { d -> categoryKeys(d).key("a").print() },
        action = { d, _ -> categoryKeys(d).forget("a") },
        check = { d, k, finished, where ->
            val f = File(historyKeys, "category-a.key")
            if (finished) assertFalse(where, d.exists(f))
            else if (d.exists(f)) assertArrayEquals(where, k, categoryKeys(d).key("a").print())
        },
    )

    private val vaultFile = File("/data/files/vault/7.enc")
    private val tmpVaultFile = File("/data/files/vault/7.enc.tmp")
    private val fileKey = ByteArray(Cipher256.KEY) { (it * 3).toByte() }
    private val content = ByteArray(VaultFile.CHUNK + 777) { (it % 253).toByte() }

    private fun readVault(d: CrashDisk) = VaultFile.Reader(d, vaultFile, keyOf(fileKey)).stream().use { it.readBytes() }

    @Test
    fun aVaultFileWrittenAsideAndRenamed() = Crashes.everywhere(
        setup = { d -> d.mkdirs(vaultFile.parentFile) },
        action = { d, _ ->
            VaultFile.writer(d.output(tmpVaultFile, sync = true), Cipher256.XCHACHA, keyOf(fileKey)).use { it.write(content) }
            d.renameDurably(tmpVaultFile, vaultFile)
        },
        check = { d, _, finished, where ->
            if (finished || d.exists(vaultFile)) assertArrayEquals(where, content, readVault(d))
        },
    )

    @Test
    fun wipingAVaultFile() = Crashes.everywhere(
        setup = { d ->
            d.mkdirs(vaultFile.parentFile)
            VaultFile.writer(d.output(vaultFile, sync = true), Cipher256.XCHACHA, keyOf(fileKey)).use { it.write(content) }
        },
        action = { d, _ -> VaultFile.wipe(d, vaultFile) },
        check = { d, _, finished, where ->
            if (finished) assertFalse(where, d.exists(vaultFile))
        },
    )

    // A ring whose password boxes are AES, sealed again in XChaCha: the password opens it at every stop.
    private fun rewrapRing(mode: KeyRing.Mode) = Crashes.everywhere(
        setup = { d ->
            val device = CrashDevice(d)
            val r = KeyRing(d, dir, device, kdf, cipher = { Cipher256.AES }).apply { create() }
            val keys = KeyRing.DOMAINS.associateWith { r.key(it).print() }
            r.setMode(mode, pw("pw"))
            assertTrue(KeyRing(d, dir, device, kdf).apply { unlock(pw("pw")) }.needsRewrap())
            Ring(device, keys)
        },
        action = { d, s -> KeyRing(d, dir, s.device, kdf).apply { unlock(pw("pw")); rewrap() } },
        check = { d, s, finished, where ->
            val r = KeyRing(d, dir, s.device, kdf)
            assertEquals(where, mode, r.mode)
            r.unlock(pw("pw"))
            assertKeys(r, s.keys, where)
            if (finished) assertFalse(where, r.needsRewrap())
            r.rewrap()
            assertFalse(where, r.needsRewrap())
            assertKeys(KeyRing(d, dir, s.device, kdf).apply { unlock(pw("pw")) }, s.keys, "$where, after a second rewrap")
            assertNoLeftovers(d, where)
        },
    )

    @Test
    fun rewrapPasswordRing() = rewrapRing(KeyRing.Mode.PASSWORD)

    @Test
    fun rewrapBothRing() = rewrapRing(KeyRing.Mode.BOTH)

    @Test
    fun rewrapCategoryKeys() = Crashes.everywhere(
        setup = { d ->
            val old = CategoryKeys(d, historyKeys, { op -> op(domain) }) { Cipher256.AES }
            listOf("a", "b", "c").associateWith { old.key(it).print() }
        },
        action = { d, _ -> categoryKeys(d).rewrap() },
        check = { d, keys, finished, where ->
            val k = categoryKeys(d)
            for ((name, key) in keys) assertArrayEquals("$where: $name", key, k.key(name).print())
            if (finished) assertFalse(where, k.needsRewrap())
        },
    )

    // Files aside that a finished or undone operation must not leave behind.
    private fun assertNoLeftovers(d: CrashDisk, where: String) {
        val left = d.files().keys.filter { it.endsWith(".mode.json") || it.endsWith("mode.commit") || it.endsWith(".next.json") }
        assertTrue("$where: $left", left.isEmpty())
    }
}
