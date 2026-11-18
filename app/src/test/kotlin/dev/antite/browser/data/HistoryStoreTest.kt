package dev.antite.browser.data

import dev.antite.browser.data.HistoryIndex.Event
import dev.antite.keys.CategoryKeys
import dev.antite.keys.Cipher256
import dev.antite.keys.Ciphers
import dev.antite.keys.RealDisk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class HistoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_800_000_000_000L
    private val domain = dev.antite.keys.CoreKey.random()
    private val keyDir get() = File(tmp.root, "keys")
    private fun keys() = CategoryKeys(RealDisk(tmp.root), keyDir, { op -> op(domain) }) { Cipher256.AES }
    private fun store(k: CategoryKeys = keys()) = HistoryStore(RealDisk(tmp.root), File(tmp.root, "history"), k::key, { Cipher256.XCHACHA }) { clock }

    @Test
    fun visitsStayInTheirCategoryAndSurviveARestart() {
        store().apply {
            visit("work", "https://work.example/", "Work")
            visit(null, "https://home.example/", "Home")
            title("work", "https://work.example/", "Work 2")
        }
        val s = store()
        assertTrue(s.visited("work", listOf("https://work.example/")).single())
        assertFalse(s.visited("work", listOf("https://home.example/")).single())
        assertEquals(listOf("Work 2"), s.list(listOf("work"), null, 10).map { it.title })
        assertEquals(2, s.list(listOf("work", null), null, 10).size)
    }

    @Test
    fun anEntryIsDeletedFromItsOwnCategory() {
        val s = store()
        s.visit("work", "https://a.example/", null)
        s.visit(null, "https://a.example/", null)
        val workEntry = s.list(listOf("work", null), null, 10).first { it.extra == "work" }
        s.delete(workEntry)
        assertFalse(s.visited("work", listOf("https://a.example/")).single())
        assertTrue(s.visited(null, listOf("https://a.example/")).single())
        assertFalse(store().visited("work", listOf("https://a.example/")).single())
    }

    @Test
    fun theLogIsCompactedAndKeepsCounts() {
        val s = store()
        repeat(300) { s.visit(null, "https://a.example/", null) }
        assertTrue("written again", File(tmp.root, "history/-.log").length() < 300 * 60)
        assertEquals(1, store().list(listOf(null), null, 10).size)
        assertEquals(listOf("a.example"), store().frequent(null, emptySet(), 5).map { it.host })
    }

    // Old pages come in even where pages were visited since (a start with the key ring locked put the move off),
    // and a page visited since keeps its own counts.
    @Test
    fun importAddsOnlyPagesNotThereYet() {
        val s = store()
        s.visit("work", "https://both.example/", "New")
        s.import("work", listOf(
            Event.Page("https://old.example/", "Old", 4, clock - 1000),
            Event.Page("https://both.example/", "Stale", 9, clock - 5000),
        ))
        val back = store().list(listOf("work"), null, 10).associate { it.url to it.title }
        assertEquals(mapOf("https://old.example/" to "Old", "https://both.example/" to "New"), back)
    }

    // A log that cannot be read is put aside and the category starts again: history must not stop for good.
    @Test
    fun aDamagedLogIsKeptAsideAndHistoryGoesOn() {
        store().visit("work", "https://a.example/", null)
        val f = File(tmp.root, "history/work.log")
        val bytes = f.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        File(tmp.root, "history/-.log").delete()
        f.writeBytes(bytes.copyOf(300) + bytes.copyOfRange(300, bytes.size))
        val s = store()
        s.visit("work", "https://b.example/", null)
        assertEquals(listOf("https://b.example/"), store().list(listOf("work"), null, 10).map { it.url })
        assertTrue(File(tmp.root, "history/work.log.bad").exists())
    }

    @Test
    fun aForgottenCategoryCannotBeReadEvenFromACopy() {
        val s = store()
        s.visit("work", "https://secret.example/", null)
        val copy = File(tmp.root, "history/work.log").readBytes()
        val k = keys()
        s.forget("work")
        k.forget("work")
        assertFalse(File(tmp.root, "history/work.log").exists())
        File(tmp.root, "history/work.log").writeBytes(copy)
        assertFalse(store().visited("work", listOf("https://secret.example/")).single())
        assertTrue("the unreadable copy is put aside", File(tmp.root, "history/work.log.bad").exists())
    }

    // A new cipher setting: every log written before is sealed again, and nothing in it is lost.
    @Test
    fun recryptSealsOldLogsInTheNewCipher() {
        var c = Cipher256.AES
        val k = keys()
        val old = HistoryStore(RealDisk(tmp.root), File(tmp.root, "history"), k::key, { c }) { clock }
        old.visit("work", "https://work.example/", "Work")
        old.visit(null, "https://home.example/", "Home")
        old.close()
        c = Cipher256.XCHACHA
        val s = HistoryStore(RealDisk(tmp.root), File(tmp.root, "history"), k::key, { c }) { clock }
        assertTrue(s.needsRecrypt())
        s.recrypt()
        assertFalse(s.needsRecrypt())
        s.close()
        val again = HistoryStore(RealDisk(tmp.root), File(tmp.root, "history"), k::key, { c }) { clock }
        assertFalse(again.needsRecrypt())
        assertEquals(listOf("Home", "Work"), again.list(listOf("work", null), null, 10).mapNotNull { it.title }.sorted())
    }
}
