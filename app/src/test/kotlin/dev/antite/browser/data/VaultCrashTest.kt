package dev.antite.browser.data

import dev.antite.browser.data.MediaVault.Item
import dev.antite.keys.Cipher256
import dev.antite.keys.CrashDevice
import dev.antite.keys.CrashDisk
import dev.antite.keys.Crashes
import dev.antite.keys.KeyRing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

// Every step of the secure storage cut short by a kill, a power cut and a torn write: after the restart every file
// listed opens with what was put in it, and nothing listed before is lost.
class VaultCrashTest {
    private val files = File("/data/files")
    private val dir = File(files, "vault")
    private val kdf = KeyRing.Kdf { password, salt, _ -> MessageDigest.getInstance("SHA-512").digest(password + salt) }
    private val content = mapOf(
        "a" to ByteArray(70_000) { (it % 251).toByte() },
        "b" to ByteArray(3_000) { (it % 13).toByte() },
    )

    private fun core(d: CrashDisk, device: CrashDevice, cipher: Cipher256 = Cipher256.XCHACHA) = VaultCore(
        d, dir, KeyRing(d, File(files, "keys"), device, kdf).apply { if (!exists) create() },
        { error("no version 1 here") }, { cipher }, { false }, { m, e -> throw if (e is CrashDisk.Death) e else AssertionError(m, e) },
        // Late enough that whatever a stop left behind counts as abandoned and is tidied away.
        now = { d.clock + 3_600_000 },
    )

    private fun item(c: VaultCore, name: String) = Item(c.newId(), name, "application/octet-stream", 0, 1, "")

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
            c.items = listOf(Item(5, "a", "application/octet-stream", content.getValue("a").size.toLong(), 1, ""))
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
