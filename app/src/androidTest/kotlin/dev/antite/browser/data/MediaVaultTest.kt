package dev.antite.browser.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.antite.keys.Cipher256
import dev.antite.keys.Ciphers
import dev.antite.keys.KeyRing
import dev.antite.keys.VaultFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class MediaVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var keyDir: File
    private lateinit var ring: KeyRing
    private var cipher = Cipher256.AES
    private val prefix = "antite-test-mv"

    @Before
    fun setUp() {
        val base = File(context.cacheDir, "vault-test").apply { deleteRecursively() }
        dir = File(base, "vault").apply { mkdirs() }
        keyDir = File(base, "keys")
        ring = newRing().apply { create() }
    }

    @After
    fun tearDown() {
        dir.parentFile!!.deleteRecursively()
        for (d in KeyRing.DOMAINS) for (gen in 1..10) runCatching { Secure.Keystore.destroy("$prefix-$d-$gen") }
    }

    private fun newRing() = KeyRing(keyDir, Secure.Keystore, Secure.Argon2, prefix)

    // The device key of version 1 vaults, played by a mask.
    private val legacy: (ByteArray) -> ByteArray = { b -> ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() } }

    private fun vault(r: KeyRing = ring) = MediaVault(context, dir, r, legacy, { cipher }, { false }, {})

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            assertTrue(what, System.currentTimeMillis() < end)
            Thread.sleep(50)
        }
    }

    private fun content(v: MediaVault, item: MediaVault.Item) = v.reader(item).stream().use { it.readBytes() }

    private fun noneHolds(text: String) {
        for (f in dir.listFiles()!!) {
            assertFalse("${f.name} holds '$text' in the open", String(f.readBytes(), Charsets.ISO_8859_1).contains(text))
        }
    }

    // A version 1 file: the key wrapped in the header, AES chunks.
    private fun v1File(name: String, plain: ByteArray) {
        val key32 = Ciphers.randomBytes(32)
        val blob = legacy(key32)
        val head = ByteBuffer.allocate(VaultFile.HEADER).put("AVLT".toByteArray()).put(1).put(1).put(blob.size.toByte()).put(blob).array()
        val chunks = maxOf(1, (plain.size + VaultFile.CHUNK - 1) / VaultFile.CHUNK)
        val body = (0 until chunks).map { i ->
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).putLong(i.toLong()).array()))
            c.updateAAD(ByteBuffer.allocate(14).put("AVLT".toByteArray()).put(1).putLong(i.toLong()).put(if (i == chunks - 1) 1 else 0).array())
            c.doFinal(plain, i * VaultFile.CHUNK, minOf(VaultFile.CHUNK, plain.size - i * VaultFile.CHUNK))
        }
        File(dir, name).writeBytes(body.fold(head) { a, b -> a + b })
    }

    private fun addFile(v: MediaVault, name: String, data: ByteArray): MediaVault.Item {
        val item = v.reserve(name, "application/octet-stream", "")
        v.output(item).use { it.write(data) }
        val before = v.items.size
        v.receive(item, data.size.toLong())
        waitFor("file indexed") { v.items.size == before + 1 }
        return v.items.single { it.id == item.id }
    }

    @Test
    fun filesFromBeforeTheEncryptionAreEncryptedInPlace() {
        val photo = Random(3).nextBytes(3 * VaultFile.CHUNK + 5)
        File(dir, "4").writeBytes(photo)
        File(dir, "index.json").writeText(
            """[{"id":4,"name":"Secret name.png","mime":"image/png","size":${photo.size},"time":1,"source":"example.com"}]""")

        val vault = vault()
        assertEquals(listOf("Secret name.png"), vault.items.map { it.name })
        waitFor("old files encrypted") { vault.pending.isEmpty() && !File(dir, "index.json").exists() }

        assertEquals(setOf("4.enc", "index.box"), dir.list()!!.toSet())
        assertArrayEquals(photo, content(vault, vault.items.single()))
        noneHolds("Secret name")

        val again = vault()
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

        val vault = vault()
        waitFor("old files encrypted") { vault.pending.isEmpty() && !File(dir, "index.json").exists() }

        assertEquals(setOf("3.enc", "4.enc", "index.box"), dir.list()!!.toSet())
        val stray = vault.items.single { it.id == 3L }
        assertEquals("image/png", stray.mime)
        assertArrayEquals(png, content(vault, stray))
        assertEquals(setOf(3L, 4L), vault().items.map { it.id }.toSet())
    }

    // Version 1 to the key ring: the keys move into the index, the files keep their chunks.
    @Test
    fun aVersion1VaultMovesOverWithoutReencrypting() {
        val a = Random(11).nextBytes(2 * VaultFile.CHUNK + 9)
        val b = Random(12).nextBytes(10)
        v1File("1.enc", a)
        v1File("2.enc", b)
        val json = """{"items":[{"id":1,"name":"Secret a","mime":"x/y","size":${a.size},"time":1,"source":""},""" +
            """{"id":2,"name":"b","mime":"x/y","size":10,"time":1,"source":"","folder":3}],"folders":[{"id":3,"name":"F"}]}"""
        // The old index is a version 1 file too.
        val idx = File(dir, "index.tmp")
        v1File("index.tmp", json.toByteArray())
        idx.renameTo(File(dir, "index.enc"))
        val chunksBefore = File(dir, "1.enc").readBytes().let { it.copyOfRange(VaultFile.HEADER, it.size) }

        val vault = vault()
        waitFor("moved over") { vault.pending.isEmpty() && !File(dir, "index.enc").exists() }
        assertEquals(setOf("1.enc", "2.enc", "index.box"), dir.list()!!.toSet())
        for (n in listOf("1.enc", "2.enc")) assertEquals(2, VaultFile.head(File(dir, n)).version)
        File(dir, "1.enc").readBytes().let { assertArrayEquals(chunksBefore, it.copyOfRange(VaultFile.HEADER, it.size)) }
        assertArrayEquals(a, content(vault, vault.items.single { it.id == 1L }))
        assertArrayEquals(b, content(vault, vault.items.single { it.id == 2L }))
        assertEquals(listOf("F"), vault.folders.map { it.name })
        noneHolds("Secret a")

        val again = vault()
        assertArrayEquals(a, content(again, again.items.single { it.id == 1L }))
    }

    @Test
    fun foldersAndTheirFilesSurviveARestart() {
        val vault = vault()
        var folder: MediaVault.Folder? = null
        vault.addFolder("Docs") { folder = it }
        waitFor("folder added") { folder != null }
        val item = addFile(vault, "a.bin", byteArrayOf(1, 2, 3))
        vault.move(item, folder!!.id)
        waitFor("file moved") { vault.items.single().folder == folder!!.id }

        val again = vault()
        assertEquals(listOf("Docs"), again.folders.map { it.name })
        assertEquals(folder!!.id, again.items.single().folder)

        again.deleteFolder(again.folders.single())
        waitFor("folder deleted") { again.folders.isEmpty() }
        assertEquals("the file stays, without a folder", null, again.items.single().folder)
        assertArrayEquals(byteArrayOf(1, 2, 3), content(again, again.items.single()))
    }

    @Test
    fun unreadableIndexIsNotWrittenOver() {
        File(dir, "index.box").writeBytes(Random(1).nextBytes(500))
        val before = File(dir, "index.box").readBytes()
        val vault = vault()
        var done = false
        vault.addFolder("x") { done = true }
        waitFor("change attempted") { done }
        assertArrayEquals(before, File(dir, "index.box").readBytes())
    }

    // The deleted file's key was only in the index; every copy of the old index is sealed by a destroyed key.
    @Test
    fun aDeletedFileCannotBeReadFromOldCopies() {
        val vault = vault()
        addFile(vault, "keep", byteArrayOf(1))
        val gone = addFile(vault, "gone", Random(2).nextBytes(1000))
        val oldIndex = File(dir, "index.box").readBytes()
        val oldKeyFile = File(keyDir, "vault.json").readText()

        var deleted = false
        vault.delete(gone) { deleted = true }
        waitFor("deleted") { deleted }
        assertFalse(File(dir, "${gone.id}.enc").exists())
        assertThrows(IOException::class.java) { Ciphers.unbox(ring.key(KeyRing.VAULT), oldIndex, "antite-vault-index".toByteArray()) }
        File(keyDir, "vault.json").writeText(oldKeyFile)
        assertThrows(Exception::class.java) { newRing().key(KeyRing.VAULT) }
    }

    @Test
    fun anotherCipherReencryptsEveryFile() {
        val vault = vault()
        val data = Random(4).nextBytes(VaultFile.CHUNK + 3)
        addFile(vault, "x", data)
        cipher = Cipher256.CASCADE
        var done = false
        vault.recrypt(Cipher256.CASCADE) { done = true }
        waitFor("re-encrypted") { done }
        val item = vault.items.single()
        assertEquals(Cipher256.CASCADE, VaultFile.head(File(dir, "${item.id}.enc")).cipher)
        assertArrayEquals(data, content(vault, item))
        assertEquals(setOf("${item.id}.enc", "index.box"), dir.list()!!.toSet())
        assertArrayEquals(data, vault().let { content(it, it.items.single()) })
    }

    // With a password the vault closes with the key ring; a download that ends meanwhile joins at the next open.
    @Test
    fun aDownloadFinishedWhileLockedJoinsLater() {
        ring.setMode(KeyRing.Mode.PASSWORD, "pw".toByteArray(), KeyRing.KdfParams(1, 1024, 1))
        val vault = vault()
        assertTrue(vault.opened)
        val item = vault.reserve("late.bin", "application/octet-stream", "")
        val out = vault.output(item)
        vault.close()
        ring.lock()
        assertTrue(vault.items.isEmpty())
        assertThrows(KeyRing.LockedException::class.java) { vault.reserve("x", "y", "") }
        out.use { it.write(byteArrayOf(7, 8)) }
        vault.receive(item, 2)
        Thread.sleep(300)
        assertTrue("nothing written while locked", vault.items.isEmpty())

        ring.unlock("pw".toByteArray())
        vault.open()
        waitFor("joined") { vault.items.size == 1 }
        waitFor("index saved") { File(dir, "index.box").exists() }
        Thread.sleep(300)
        assertEquals(1, vault().items.size)
        assertArrayEquals(byteArrayOf(7, 8), content(vault, vault.items.single()))
    }
}
