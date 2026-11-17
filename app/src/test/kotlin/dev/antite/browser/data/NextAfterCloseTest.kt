package dev.antite.browser.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NextAfterCloseTest {
    private fun tab(id: Long, incognito: Boolean = false, parent: Long = 0L, used: Long = 0L) =
        Tab(id, incognito).apply { parentId = parent; lastUsed = used }

    @Test
    fun theOpenerComesFirst() {
        val a = tab(1); val b = tab(2); val c = tab(3, parent = 1)
        assertSame(a, nextAfterClose(listOf(a, b), c, 2))
    }

    @Test
    fun elseTheNearestBeforeThenAfter() {
        val a = tab(1); val b = tab(2); val c = tab(3); val d = tab(4)
        assertSame(b, nextAfterClose(listOf(a, b, d), c, 2))
        assertSame(b, nextAfterClose(listOf(b, c, d), a, 0))
    }

    @Test
    fun staysOnItsSideOfIncognito() {
        val n = tab(1); val i1 = tab(2, incognito = true); val closed = tab(3, incognito = true); val n2 = tab(4)
        assertSame(i1, nextAfterClose(listOf(n, i1, n2), closed, 2))
        val normal = tab(5)
        assertSame(n, nextAfterClose(listOf(i1, n), normal, 1))
    }

    @Test
    fun theLastIncognitoGoesToTheLastUsedNormalTab() {
        val old = tab(1, used = 10); val recent = tab(2, used = 20); val closed = tab(3, incognito = true)
        assertSame(recent, nextAfterClose(listOf(old, recent), closed, 2))
        assertNull(nextAfterClose(emptyList(), closed, 0))
    }
}
