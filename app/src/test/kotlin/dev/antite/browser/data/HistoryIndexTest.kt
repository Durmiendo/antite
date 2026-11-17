package dev.antite.browser.data

import dev.antite.browser.data.HistoryIndex.Event
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryIndexTest {
    private var clock = 100L * 24 * 3600 * 1000
    private val day = 24L * 3600 * 1000
    private fun index() = HistoryIndex { clock }

    @Test
    fun visitsCountAndKeepTheLatestTitle() {
        val h = index()
        h.apply(Event.Visit("https://a.example/", "A", clock - 10))
        h.apply(Event.Visit("https://a.example/", null, clock - 5))
        h.apply(Event.Title("https://a.example/", "A2"))
        val e = h.list(null, 10).single()
        assertEquals("A2", e.title)
        assertEquals(clock - 5, e.time)
        assertEquals(listOf(Event.Page("https://a.example/", "A2", 2, clock - 5)), h.snapshot())
    }

    @Test
    fun listIsNewestFirstAndFiltered() {
        val h = index()
        h.apply(Event.Visit("https://old.example/", "Old", clock - 3 * day))
        h.apply(Event.Visit("https://new.example/", "Fresh news", clock - day))
        assertEquals(listOf("https://new.example/", "https://old.example/"), h.list(null, 10).map { it.url })
        assertEquals(listOf("https://new.example/"), h.list("NEWS", 10).map { it.url })
        assertEquals(1, h.list(null, 1).size)
    }

    @Test
    fun pagesOlderThanNinetyDaysAreGone() {
        val h = index()
        h.apply(Event.Visit("https://gone.example/", null, clock - 91 * day))
        h.apply(Event.Visit("https://kept.example/", null, clock - 89 * day))
        assertEquals(listOf("https://kept.example/"), h.list(null, 10).map { it.url })
        assertEquals(1, h.snapshot().size)
    }

    @Test
    fun delete() {
        val h = index()
        h.apply(Event.Visit("https://a.example/", null, clock))
        h.apply(Event.Visit("https://b.example/", null, clock))
        val id = h.list("a.example", 1).single().id
        assertEquals("https://a.example/", h.urlOf(id))
        h.apply(Event.Delete("https://a.example/"))
        assertArrayEquals(booleanArrayOf(false, true), h.visited(listOf("https://a.example/", "https://b.example/")))
    }

    @Test
    fun suggestionsPreferTheHostThenVisits() {
        val h = index()
        h.apply(Event.Visit("https://docs.example/rust", "Rust", clock))
        repeat(3) { h.apply(Event.Visit("https://search.example/?q=rust", "rust - search", clock)) }
        h.apply(Event.Visit("https://rust.example/", "Home", clock - day))
        assertEquals(listOf("https://rust.example/", "https://search.example/?q=rust", "https://docs.example/rust"),
            h.suggest("rust", 5).map { it.url })
        assertTrue(h.suggest("  ", 5).isEmpty())
    }

    @Test
    fun frequentSitesAreHostsOfTheLastMonth() {
        val h = index()
        repeat(3) { h.apply(Event.Visit("https://www.a.example/x", "A", clock)) }
        h.apply(Event.Visit("https://a.example/y", null, clock))
        repeat(2) { h.apply(Event.Visit("https://b.example/", "B", clock)) }
        repeat(9) { h.apply(Event.Visit("https://old.example/", null, clock - 31 * day)) }
        h.apply(Event.Visit("https://skip.example/", null, clock))
        val sites = h.frequent(setOf("skip.example"), 10)
        assertEquals(listOf("a.example", "b.example"), sites.map { it.host })
        assertEquals("https://www.a.example/", sites[0].url)
    }

    @Test
    fun compactingIsWorthItOnlyAfterManyEvents() {
        val h = index()
        repeat(200) { h.apply(Event.Visit("https://a.example/", null, clock)) }
        assertFalse(h.worthCompacting)
        repeat(10) { h.apply(Event.Visit("https://a.example/", null, clock)) }
        assertTrue(h.worthCompacting)
        h.compacted()
        assertFalse(h.worthCompacting)
    }
}
