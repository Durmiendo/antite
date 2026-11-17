package dev.antite.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RealDiskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "data").apply { mkdirs() }
    private val outside get() = File(tmp.root, "outside").apply { mkdirs() }

    // Gecko keeps a link named "lock" in its profile, pointing nowhere real.
    @Test
    fun aFolderWithLinksIsDeletedWithoutFollowingThem() {
        val disk = RealDisk(root)
        val profile = File(root, "files/mozilla/x.default").apply { mkdirs() }
        File(profile, "prefs.js").writeText("prefs")
        val kept = File(outside, "kept.txt").apply { writeText("not mine") }
        Files.createSymbolicLink(File(profile, "lock").toPath(), File("127.0.0.1:+4242").toPath())
        Files.createSymbolicLink(File(profile, "out").toPath(), kept.toPath())
        disk.deleteTree(File(root, "files"))
        assertFalse(File(root, "files").exists())
        assertEquals("not mine", kept.readText())
    }

    @Test
    fun linksAreSeenButNeverFollowed() {
        val disk = RealDisk(root)
        val dir = File(root, "d").apply { mkdirs() }
        Files.createSymbolicLink(File(dir, "out").toPath(), outside.toPath())
        assertEquals(listOf("out"), disk.children(dir).map { it.name })
        assertTrue(disk.isLink(File(dir, "out")))
        assertFalse(disk.isDirectory(File(dir, "out")))
        assertThrows(DiskException.OutsideRoot::class.java) { disk.open(File(dir, "out/x"), Disk.Open.WRITE) }
        assertThrows(DiskException.OutsideRoot::class.java) { disk.exists(File(root, "../outside")) }
    }
}
