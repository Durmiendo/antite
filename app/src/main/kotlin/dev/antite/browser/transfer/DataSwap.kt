package dev.antite.browser.transfer

import dev.antite.keys.Disk
import dev.antite.keys.deleteDurably
import dev.antite.keys.deleteTree
import dev.antite.keys.readAll
import dev.antite.keys.renameDurably
import dev.antite.keys.replace
import java.io.File

// The data folder replaced by a staged one, so that a stop anywhere leaves the old data or the new, never a mix.
// Nothing changes until commit(); from then on recover() carries the swap through, from wherever it stopped, on any
// later start. Without the commit it clears what the unfinished import left.
//   phase 1: the current entries move to OLD_DIR; phase 2: the staged ones move in, the device's own come back,
//   the imported keys replace the current ones, OLD_DIR goes.
class DataSwap(private val disk: Disk, private val root: File) {
    companion object {
        const val IN_DIR = "transfer-in"
        const val OUT_DIR = "transfer-out"
        const val OLD_DIR = "transfer-old"
        const val COMMIT = "transfer.commit"
        const val LOCK = "transfer.lock"
        val SKIPPED = setOf("cache", "code_cache", "lib", IN_DIR, OUT_DIR, OLD_DIR, COMMIT, "$COMMIT.tmp", LOCK)
        // The engine and whom it trusts for updates (sources, keys) stay this device's: a backup must not bring in
        // another update source, or a backup file from someone else would install their engine.
        // The key files are wrapped by this device's Keystore and are of no use elsewhere.
        val DEVICE_ONLY = listOf("files/engines", "shared_prefs/engines.xml", "files/keys")
    }

    val stage = File(root, IN_DIR)
    private val old = File(root, OLD_DIR)
    private val marker = File(root, COMMIT)

    fun commit() = disk.replace(marker, byteArrayOf('1'.code.toByte()))

    // keys(true) puts the imported keys in place, keys(false) forgets them; it runs once files/keys is back.
    fun recover(keys: (adopt: Boolean) -> Unit) {
        // A stop while the marker was written leaves its copy aside: the marker is either there whole or not at all.
        disk.deleteDurably(File(root, "$COMMIT.tmp"))
        if (!disk.exists(marker)) {
            if (disk.exists(stage)) {
                keys(false)
                disk.deleteTree(stage)
            }
            return
        }
        if (disk.readAll(marker).contentEquals(byteArrayOf('1'.code.toByte()))) {
            disk.mkdirs(old)
            for (f in disk.children(root)) if (f.name !in SKIPPED) disk.rename(f, File(old, f.name))
            disk.syncFolder(old)
            disk.syncFolder(root)
            disk.replace(marker, byteArrayOf('2'.code.toByte()))
        }
        if (disk.exists(stage)) {
            for (f in disk.children(stage)) disk.rename(f, File(root, f.name))
            disk.syncFolder(root)
            disk.syncFolder(stage)
            disk.deleteDurably(stage)
        }
        for (rel in DEVICE_ONLY) {
            val kept = File(old, rel)
            if (!disk.exists(kept)) continue
            val target = File(root, rel)
            disk.mkdirs(target.parentFile)
            disk.deleteTree(target)
            disk.renameDurably(kept, target)
        }
        keys(true)
        disk.deleteTree(old)
        disk.children(File(root, "cache")).forEach { disk.deleteTree(it) }
        disk.deleteDurably(marker)
    }
}
