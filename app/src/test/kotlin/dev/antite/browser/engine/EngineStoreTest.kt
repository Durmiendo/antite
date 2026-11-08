package dev.antite.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun engine(store: EngineStore, name: String, version: String): File = File(store.dir, name).apply {
        mkdirs()
        File(this, EngineStore.READY).createNewFile()
        File(this, "engine.json").writeText("""{"version":"$version"}""")
    }

    @Test
    fun pendingBecomesActiveAndOldOneIsKept() {
        val store = EngineStore(tmp.root)
        val a = engine(store, "gecko-1", "1")
        val b = engine(store, "gecko-2", "2")
        store.activate(a)
        store.setPending(b)
        assertEquals(a, store.active())

        assertTrue(store.applyPending())
        assertEquals(b, store.active())
        assertEquals(a, store.previous())
        assertNull(store.pending())
        assertFalse(store.applyPending())
        assertEquals("2", store.version(b))
    }

    @Test
    fun pendingWithoutReadyMarkIsIgnored() {
        val store = EngineStore(tmp.root)
        val a = engine(store, "gecko-1", "1")
        store.activate(a)
        val half = File(store.dir, "gecko-2").apply { mkdirs() }
        store.setPending(half)
        assertFalse(store.applyPending())
        assertEquals(a, store.active())
    }

    @Test
    fun removeUnusedKeepsActivePendingPreviousAndInstalls() {
        val store = EngineStore(tmp.root)
        val old = engine(store, "gecko-0", "0")
        val prev = engine(store, "gecko-1", "1")
        val cur = engine(store, "gecko-2", "2")
        val next = engine(store, "gecko-3", "3")
        val inProgress = File(store.dir, "gecko-4.tmp").apply { mkdirs() }
        store.activate(prev)
        store.setPending(cur)
        store.applyPending()
        store.setPending(next)

        store.removeUnused()
        assertFalse(old.exists())
        assertTrue(prev.exists() && cur.exists() && next.exists() && inProgress.exists())
    }

    @Test
    fun versionsCompareByNumbers() {
        assertTrue(EngineUpdates.newer("160.0.20261201000000", "159.0.20261102144711"))
        assertTrue(EngineUpdates.newer("159.0.20261102144712", "159.0.20261102144711"))
        assertTrue(EngineUpdates.newer("159.10", "159.9"))
        assertFalse(EngineUpdates.newer("159.0.20261102144711", "159.0.20261102144711"))
        assertFalse(EngineUpdates.newer("158.9", "159.0"))
    }
}
