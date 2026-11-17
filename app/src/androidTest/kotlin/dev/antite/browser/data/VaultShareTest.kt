package dev.antite.browser.data

import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import kotlin.random.Random

// A file shared from the storage reads back whole through its link, decrypted on the way, and the link is dead once
// the storage locks. Runs on the app's own storage (a test device), and takes its file out again.
@RunWith(AndroidJUnit4::class)
class VaultShareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            assertTrue(what, System.currentTimeMillis() < end)
            Thread.sleep(50)
        }
    }

    @Test
    fun aSharedFileReadsBackAndDiesWithTheLock() {
        val vault = MediaVault.get(context)
        waitFor("the storage opens") { vault.opened }
        val data = Random(21).nextBytes(150_000)
        val item = vault.reserve("share-test-${System.nanoTime()}.bin", "application/octet-stream", "")
        vault.output(item).use { it.write(data) }
        vault.receive(item, data.size.toLong())
        waitFor("the file joins the storage") { vault.items.any { it.id == item.id } }
        val stored = vault.items.first { it.id == item.id }
        try {
            val uri = VaultShare.uris(context, listOf(stored)).single()
            assertArrayEquals(data, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            context.contentResolver.query(uri, null, null, null, null)!!.use { c ->
                c.moveToFirst()
                assertEquals(stored.name, c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                assertEquals(data.size.toLong(), c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
            }
            VaultShare.revokeAll(context)
            val dead = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.exceptionOrNull()
            assertTrue("the link still opens: $dead", dead is FileNotFoundException)
        } finally {
            var gone = false
            vault.delete(listOf(stored)) { gone = true }
            waitFor("the file leaves the storage") { gone }
        }
    }
}
