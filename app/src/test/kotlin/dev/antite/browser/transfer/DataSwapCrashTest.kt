package dev.antite.browser.transfer

import dev.antite.keys.Cipher256
import dev.antite.keys.Ciphers
import dev.antite.keys.CrashDevice
import dev.antite.keys.CrashDisk
import dev.antite.keys.Crashes
import dev.antite.keys.KeyDomain
import dev.antite.keys.KeyRing
import dev.antite.keys.fastKdf
import dev.antite.keys.keyOf
import dev.antite.keys.output
import dev.antite.keys.print
import dev.antite.keys.syncTree
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// An import cut short anywhere, from writing the staged copy to the last step of the swap: after the next start's
// recovery the data folder and the data keys are all the old ones or all the backup's.
class DataSwapCrashTest {
    private val root = File("/data/user/0/dev.antite.browser")
    private val keysDir = File(root, "files/keys")
    private val kdf = fastKdf()

    private val before = mapOf("files/tabs.json" to "old tabs", "databases/antite.db" to "old db", "shared_prefs/antite.xml" to "old prefs")
    private val backup = mapOf("files/tabs.json" to "new tabs", "files/history/a.log" to "new log", "databases/vault.db" to "new vault")
    private val deviceOwn = mapOf("files/engines/gecko/lib.so" to "engine", "shared_prefs/engines.xml" to "sources")
    private val importedRaw = KeyRing.DOMAINS.associateWith { d -> ByteArray(Cipher256.KEY) { (it + d.name.length).toByte() } }
    private val imported = importedRaw.mapValues { (_, raw) -> keyOf(raw).use { it.print() } }

    private class Start(val device: CrashDevice, val keys: Map<KeyDomain, ByteArray>)

    private fun ring(d: CrashDisk, device: CrashDevice) = KeyRing(d, keysDir, device, kdf)

    private fun keys(d: CrashDisk, device: CrashDevice) = ring(d, device).let { r -> KeyRing.DOMAINS.associateWith { r.key(it).print() } }

    private fun recover(d: CrashDisk, device: CrashDevice) = DataSwap(d, root).recover { adopt ->
        val r = ring(d, device)
        if (adopt) r.adoptImports() else r.dropImports()
    }

    private fun contents(d: CrashDisk) = d.files()
        .filterKeys { it.startsWith(root.path + "/") && !it.startsWith("$keysDir/") }
        .mapKeys { it.key.removePrefix(root.path + "/") }
        .mapValues { String(it.value) }

    @Test
    fun anImport() = Crashes.everywhere(
        setup = { d ->
            (before + deviceOwn).forEach { (rel, text) -> d.write(File(root, rel), text.toByteArray()) }
            val device = CrashDevice(d)
            ring(d, device).create()
            Start(device, keys(d, device))
        },
        action = { d, s ->
            val swap = DataSwap(d, root)
            d.mkdirs(swap.stage)
            for ((rel, text) in backup) {
                val f = File(swap.stage, rel)
                d.mkdirs(f.parentFile)
                d.output(f, sync = true).use { it.write(text.toByteArray()) }
            }
            d.syncTree(swap.stage)
            val r = ring(d, s.device)
            for ((dom, raw) in importedRaw) r.stageImport(dom, keyOf(raw))
            swap.commit()
            recover(d, s.device)
        },
        check = { d, s, finished, where ->
            recover(d, s.device)
            val got = contents(d)
            val keys = keys(d, s.device)
            val isNew = got["files/tabs.json"] == "new tabs"
            if (finished) assertTrue(where, isNew)
            assertEquals(where, (if (isNew) backup else before) + deviceOwn, got)
            for ((dom, k) in if (isNew) imported else s.keys) assertArrayEquals("$where: $dom", k, keys[dom])
            val left = d.files().keys.filter { "transfer-" in it || it.endsWith(".import.json") || it.endsWith("transfer.commit") }
            assertTrue("$where: $left", left.isEmpty())
        },
    )
}
