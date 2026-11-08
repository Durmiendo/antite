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
        File(this, EngineStore.ABI).writeText(store.abi!!)
    }

    @Test
    fun pendingBecomesActiveAndOldOneIsKept() {
        val store = EngineStore(tmp.root, "x86_64")
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
        val store = EngineStore(tmp.root, "x86_64")
        val a = engine(store, "gecko-1", "1")
        store.activate(a)
        val half = File(store.dir, "gecko-2").apply { mkdirs(); File(this, EngineStore.ABI).writeText("x86_64") }
        store.setPending(half)
        assertFalse(store.applyPending())
        assertEquals(a, store.active())
    }

    @Test
    fun removeUnusedKeepsActivePendingPreviousAndInstalls() {
        val store = EngineStore(tmp.root, "x86_64")
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
    fun threeFailedStartsRollBack() {
        val store = EngineStore(tmp.root, "x86_64")
        val a = engine(store, "gecko-1", "1")
        val b = engine(store, "gecko-2", "2")
        store.activate(a)
        store.setPending(b)
        store.applyPending()
        repeat(EngineStore.MAX_FAILED_STARTS - 1) { store.countStart() }
        assertFalse(store.rollbackIfCrashing())
        store.countStart()
        assertTrue(store.rollbackIfCrashing())
        assertEquals(a, store.active())
        assertNull(store.previous())
        assertEquals(setOf("gecko-2"), store.rejected())
        assertEquals("rollback 2 1", store.takeNotice())
        assertNull(store.takeNotice())
        repeat(EngineStore.MAX_FAILED_STARTS) { store.countStart() }
        assertFalse(store.rollbackIfCrashing())
    }

    @Test
    fun successfulStartResetsTheCount() {
        val store = EngineStore(tmp.root, "x86_64")
        val a = engine(store, "gecko-1", "1")
        val b = engine(store, "gecko-2", "2")
        store.activate(a)
        store.setPending(b)
        store.applyPending()
        repeat(EngineStore.MAX_FAILED_STARTS - 1) { store.countStart() }
        store.markStarted()
        store.countStart()
        assertFalse(store.rollbackIfCrashing())
        assertEquals(b, store.active())
    }

    @Test
    fun keysParseFromPemOrBase64() {
        val pem = java.io.File("../keys/publisher.pub.pem").readText()
        val b64 = Sources.parseKey(pem)
        assertEquals(b64, Sources.parseKey(pem.replace("\n", "")))
        assertEquals(b64, Sources.parseKey(b64))
        assertEquals(32, Sources.fingerprint(b64).split(':').size)
        org.junit.Assert.assertThrows(SourceException::class.java) { Sources.parseKey("not a key") }
    }

    @Test
    fun versionsCompareByNumbers() {
        assertTrue(EngineUpdates.newer("160.0.20261201000000", "159.0.20261102144711"))
        assertTrue(EngineUpdates.newer("159.0.20261102144712", "159.0.20261102144711"))
        assertTrue(EngineUpdates.newer("159.10", "159.9"))
        assertTrue("adapter revision", EngineUpdates.newer("159.0.20261102144711.1", "159.0.20261102144711"))
        assertFalse(EngineUpdates.newer("159.0.20261102144711", "159.0.20261102144711.1"))
        assertFalse(EngineUpdates.newer("159.0.20261102144711", "159.0.20261102144711"))
        assertFalse(EngineUpdates.newer("158.9", "159.0"))
    }
}
