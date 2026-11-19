package dev.antite.browser.data

import dev.antite.browser.data.MediaVault.Item
import dev.antite.keys.Cipher256
import dev.antite.keys.CrashDevice
import dev.antite.keys.CrashDisk
import dev.antite.keys.Crashes
import dev.antite.keys.KeyRing
import dev.antite.keys.replace
import dev.antite.keys.readAll
import dev.antite.keys.output
import dev.antite.keys.keyOf
import dev.antite.keys.VaultFile
import dev.antite.keys.Ciphers
import dev.antite.keys.fastKdf
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Every step of the secure storage cut short by a kill, a power cut and a torn write: after the restart every file
// listed opens with what was put in it, and nothing listed before is lost.
class VaultCrashTest {
    private val files = File("/data/files")
    private val dir = File(files, "vault")
    private val kdf = fastKdf()
    private val content = mapOf(
        "a" to ByteArray(70_000) { (it % 251).toByte() },
        "b" to ByteArray(3_000) { (it % 13).toByte() },
        "c" to ByteArray(5_000) { (it % 7).toByte() },
    )

    private fun core(d: CrashDisk, device: CrashDevice, cipher: Cipher256 = Cipher256.XCHACHA) = VaultCore(
        d, dir, KeyRing(d, File(files, "keys"), device, kdf).apply { if (!exists) create() },
        { error("no version 1 here") }, { cipher }, { false }, { m, e -> throw if (e is CrashDisk.Death) e else AssertionError(m, e) },
        // Late enough that whatever a stop left behind counts as abandoned and is tidied away.
        now = { d.clock + 3_600_000 },
    )

    private fun item(c: VaultCore, name: String) = Item(c.newFileId(), name, "application/octet-stream", 0, 1, "")

    private fun opened(d: CrashDisk, device: CrashDevice) = core(d, device).apply { open() }

    private fun withFiles(vararg names: String): (CrashDisk) -> CrashDevice = { d ->
        val device = CrashDevice(d)
        val c = opened(d, device)
        for (n in names) c.store(content.getValue(n).inputStream(), c.begin(item(c, n)))
        device
    }

    // What the vault lists after the restart, each file read back whole.
    private fun listed(d: CrashDisk, device: CrashDevice, where: String): Map<String, ByteArray> {
        val c = opened(d, device)
        val out = c.items.associate { it.name to c.reader(it).stream().use { s -> s.readBytes() } }
        assertEquals("$where: names listed twice", c.items.size, out.size)
        return out
    }

    private fun assertHolds(got: Map<String, ByteArray>, names: Collection<String>, where: String) {
        for (n in names) assertArrayEquals("$where: $n", content.getValue(n), got[n] ?: throw AssertionError("$where: $n lost"))
    }

    private fun assertNoStrays(d: CrashDisk, device: CrashDevice, where: String) {
        val c = opened(d, device)
        val known = c.items.map { it.id.toString() }.toSet()
        val stray = d.files().keys.map(::File).filter { it.parentFile == dir && it.name.substringBefore('.') !in known + "index" }
        assertTrue("$where: $stray", stray.isEmpty())
    }

    private fun ring(d: CrashDisk, device: CrashDevice) = KeyRing(d, File(files, "keys"), device, kdf).apply { if (!exists) create() }

    // An index of version 2 held the file keys themselves: the first open puts them into the core, boxes them and
    // writes the index again as version 3. A stop anywhere leaves one index or the other, and every file opens.
    @Test
    fun aVersion2IndexMovesToBoxedKeys() {
        val raw = mapOf(1L to Ciphers.randomBytes(Cipher256.KEY), 2L to Ciphers.randomBytes(Cipher256.KEY))
        val names = mapOf(1L to "a", 2L to "b")
        Crashes.everywhere(
            setup = { d ->
                val device = CrashDevice(d)
                d.mkdirs(dir)
                for ((id, k) in raw) {
                    VaultFile.writer(d.output(File(dir, "$id.enc"), sync = true), Cipher256.XCHACHA, keyOf(k)).use { it.write(content.getValue(names.getValue(id))) }
                }
                val items = org.json.JSONArray().apply {
                    for ((id, n) in names) put(org.json.JSONObject().put("id", id).put("name", n).put("mime", "application/octet-stream")
                        .put("size", content.getValue(n).size).put("time", 1).put("source", ""))
                }
                val json = org.json.JSONObject().put("items", items).put("folders", org.json.JSONArray()).toString().toByteArray()
                val plain = java.nio.ByteBuffer.allocate(4 + json.size + raw.size * (8 + Cipher256.KEY)).putInt(json.size).put(json)
                    .apply { raw.forEach { (id, k) -> putLong(id).put(k) } }.array()
                d.replace(File(dir, "index.box"), Ciphers.box(Cipher256.XCHACHA, ring(d, device).key(KeyRing.VAULT), plain, "antite-vault-index".toByteArray()))
                device
            },
            action = { d, device -> opened(d, device) },
            check = { d, device, finished, where ->
                assertHolds(listed(d, device, where), listOf("a", "b"), where)
                val stored = Ciphers.unbox(ring(d, device).key(KeyRing.VAULT), d.readAll(File(dir, "index.box")), "antite-vault-index".toByteArray())
                assertFalse(where, VaultIndex.decode(stored).raw)
            },
        )
    }

    @Test
    fun addingAFile() = Crashes.everywhere(
        setup = withFiles("a"),
        action = { d, device -> opened(d, device).let { c -> c.store(content.getValue("b").inputStream(), c.begin(item(c, "b"))) } },
        check = { d, device, finished, where ->
            val got = listed(d, device, where)
            assertHolds(got, if (finished) listOf("a", "b") else got.keys, where)
            assertTrue(where, "a" in got)
            assertNoStrays(d, device, where)
        },
    )

    @Test
    fun aDownloadStraightIn() = Crashes.everywhere(
        setup = withFiles("a"),
        action = { d, device ->
            val c = opened(d, device)
            val i = c.reserve(item(c, "b"))
            c.output(i).use { it.write(content.getValue("b")) }
            c.receive(i, content.getValue("b").size.toLong())
        },
        check = { d, device, finished, where ->
            val got = listed(d, device, where)
            assertHolds(got, if (finished) listOf("a", "b") else got.keys, where)
            assertTrue(where, "a" in got)
            assertNoStrays(d, device, where)
        },
    )

    @Test
    fun deletingAFile() = Crashes.everywhere(
        setup = withFiles("a", "b"),
        action = { d, device -> opened(d, device).let { c -> c.remove(c.items.first { it.name == "b" }) } },
        check = { d, device, finished, where ->
            val got = listed(d, device, where)
            assertHolds(got, got.keys, where)
            assertTrue(where, "a" in got)
            if (finished) assertFalse(where, "b" in got)
            assertNoStrays(d, device, where)
        },
    )

    @Test
    fun deletingSeveralAtOnce() = Crashes.everywhere(
        setup = withFiles("a", "b", "c"),
        action = { d, device -> opened(d, device).let { c -> c.removeAll(c.items.filter { it.name != "c" }) } },
        check = { d, device, finished, where ->
            val got = listed(d, device, where)
            assertHolds(got, got.keys, where)
            // One index write takes both out: never one without the other.
            assertTrue("$where: ${got.keys}", got.keys == setOf("a", "b", "c") || got.keys == setOf("c"))
            if (finished) assertEquals(where, setOf("c"), got.keys)
            assertNoStrays(d, device, where)
        },
    )

    @Test
    fun aNewCipher() = Crashes.everywhere(
        setup = withFiles("a", "b"),
        action = { d, device -> opened(d, device).let { c -> c.items.forEach { c.recrypt(it, Cipher256.CASCADE) }; c.saveRotated() } },
        check = { d, device, finished, where ->
            val got = listed(d, device, where)
            assertHolds(got, listOf("a", "b"), where)
            if (finished) opened(d, device).let { c -> assertTrue(where, c.items.all { c.cipherOf(it) == Cipher256.CASCADE }) }
            assertNoStrays(d, device, where)
        },
    )

    @Test
    fun anOpenFileOfAnOldVersionIsEncrypted() = Crashes.everywhere(
        setup = { d ->
            val device = CrashDevice(d)
            val c = opened(d, device)
            c.items = listOf(Item(VaultFileId(5), "a", "application/octet-stream", content.getValue("a").size.toLong(), 1, ""))
            c.save()
            d.write(File(dir, "5"), content.getValue("a"))
            device
        },
        action = { d, device -> core(d, device).apply { open(); moveOver { } } },
        check = { d, device, _, where ->
            // The next start carries the move on from wherever it stopped.
            core(d, device).apply { open(); moveOver { } }
            assertFalse(where, d.exists(File(dir, "5")))
            assertHolds(listed(d, device, where), listOf("a"), where)
        },
    )
}
