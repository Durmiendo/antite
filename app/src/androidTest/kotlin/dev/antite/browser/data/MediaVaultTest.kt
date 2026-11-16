package dev.antite.browser.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class MediaVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "vault-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            assertTrue(what, System.currentTimeMillis() < end)
            Thread.sleep(50)
        }
    }

    @Test
    fun filesFromBeforeTheEncryptionAreEncryptedInPlace() {
        val photo = Random(3).nextBytes(3 * VaultFile.CHUNK + 5)
        File(dir, "4").writeBytes(photo)
        File(dir, "index.json").writeText(
            """[{"id":4,"name":"Secret name.png","mime":"image/png","size":${photo.size},"time":1,"source":"example.com"}]""")

        val vault = MediaVault(context, dir)
        assertEquals(listOf("Secret name.png"), vault.items.map { it.name })
        waitFor("old files encrypted") { vault.pending.isEmpty() && File(dir, "index.enc").exists() }

        assertEquals(setOf("4.enc", "index.enc"), dir.list()!!.toSet())
        assertArrayEquals(photo, vault.reader(vault.items.single()).stream().use { it.readBytes() })
        for (f in dir.listFiles()!!) {
            assertFalse("${f.name} holds the file name in the open",
                String(f.readBytes(), Charsets.ISO_8859_1).contains("Secret name"))
        }

        val again = MediaVault(context, dir)
        assertEquals(vault.items, again.items)
        assertTrue(again.pending.isEmpty())
    }

    @Test
    fun anOpenFileMissingFromTheIndexIsTakenInAndEncrypted() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            Random(5).nextBytes(100)
        File(dir, "4").writeBytes(byteArrayOf(1))
        File(dir, "3").writeBytes(png)
        File(dir, "index.json").writeText("""[{"id":4,"name":"a.bin","mime":"application/octet-stream","size":1,"time":1,"source":""}]""")

        val vault = MediaVault(context, dir)
        waitFor("old files encrypted") { vault.pending.isEmpty() && File(dir, "index.enc").exists() }

        assertEquals(setOf("3.enc", "4.enc", "index.enc"), dir.list()!!.toSet())
        val stray = vault.items.single { it.id == 3L }
        assertEquals("image/png", stray.mime)
        assertArrayEquals(png, vault.reader(stray).stream().use { it.readBytes() })
        assertEquals(setOf(3L, 4L), MediaVault(context, dir).items.map { it.id }.toSet())
    }

    @Test
    fun foldersAndTheirFilesSurviveARestart() {
        val vault = MediaVault(context, dir)
        var folder: MediaVault.Folder? = null
        vault.addFolder("Docs") { folder = it }
        waitFor("folder added") { folder != null }
        val item = vault.reserve("a.bin", "application/octet-stream", "")
        vault.output(item).use { it.write(byteArrayOf(1, 2, 3)) }
        vault.receive(item, 3)
        waitFor("file indexed") { vault.items.size == 1 }
        vault.move(vault.items.single(), folder!!.id)
        waitFor("file moved") { vault.items.single().folder == folder!!.id }

        val again = MediaVault(context, dir)
        assertEquals(listOf("Docs"), again.folders.map { it.name })
        assertEquals(folder!!.id, again.items.single().folder)

        again.deleteFolder(again.folders.single())
        waitFor("folder deleted") { again.folders.isEmpty() }
        assertEquals("the file stays, without a folder", null, again.items.single().folder)
        assertArrayEquals(byteArrayOf(1, 2, 3), again.reader(again.items.single()).stream().use { it.readBytes() })
    }

    @Test
    fun unreadableIndexIsNotWrittenOver() {
        File(dir, "index.enc").writeBytes(Random(1).nextBytes(500))
        val before = File(dir, "index.enc").readBytes()
        val vault = MediaVault(context, dir)
        var done = false
        vault.addFolder("x") { done = true }
        waitFor("change attempted") { done }
        assertArrayEquals(before, File(dir, "index.enc").readBytes())
    }
}
