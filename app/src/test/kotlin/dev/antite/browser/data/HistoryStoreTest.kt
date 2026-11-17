package dev.antite.browser.data

import dev.antite.browser.data.HistoryIndex.Event
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
    private val domain = Ciphers.randomBytes(Cipher256.KEY)
    private val keyDir get() = File(tmp.root, "keys")
    private fun keys() = CategoryKeys(keyDir, { domain }) { Cipher256.AES }
    private fun store(k: CategoryKeys = keys()) = HistoryStore(File(tmp.root, "history"), k::key, { Cipher256.XCHACHA }) { clock }

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

    @Test
    fun importFillsOnlyAnEmptyCategory() {
        val pages = listOf(Event.Page("https://old.example/", "Old", 4, clock - 1000))
        val s = store()
        s.import("work", pages)
        s.visit("home", "https://x.example/", null)
        s.import("home", pages)
        assertEquals(listOf("https://old.example/"), store().list(listOf("work"), null, 10).map { it.url })
        assertEquals(listOf("https://x.example/"), store().list(listOf("home"), null, 10).map { it.url })
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
        assertThrows(IOException::class.java) { store().visited("work", listOf("https://secret.example/")) }
    }

    @Test
    fun categoryKeysAreRandomAndSealed() {
        val k = keys()
        val a = k.key("a").copyOf()
        assertFalse(a.contentEquals(k.key("b")))
        assertArrayEquals(a, keys().key("a"))
        assertThrows(IOException::class.java) {
            CategoryKeys(keyDir, { Ciphers.randomBytes(Cipher256.KEY) }) { Cipher256.AES }.key("a")
        }
    }
}
