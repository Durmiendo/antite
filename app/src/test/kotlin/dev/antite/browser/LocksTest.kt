package dev.antite.browser

import dev.antite.browser.data.CategoryId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocksTest {
    private val work = CategoryId("work")
    private val home = CategoryId("home")
    private var clock = 1_000_000L

    private class Settings(
        override var incognitoLock: Boolean = false,
        val locked: MutableSet<CategoryId> = mutableSetOf(),
        override var vaultLockOnExit: Boolean = true,
        override var vaultLockMinutes: Int? = null,
    ) : Locks.Settings {
        override fun categoryLock(id: CategoryId) = id in locked
    }

    private fun locks(s: Settings) = Locks(s) { clock }

    @Test
    fun incognitoIsLockedOnlyWithTheSettingAndUntilUnlocked() {
        val s = Settings()
        val l = locks(s)
        assertFalse(l.incognitoLocked)
        s.incognitoLock = true
        assertTrue(l.incognitoLocked)
        assertTrue(l.tabLocked(incognito = true, category = null))
        l.incognitoUnlocked = true
        assertFalse(l.incognitoLocked)
        l.left()
        assertTrue(l.incognitoLocked)
    }

    @Test
    fun aCategoryIsLockedOnlyWithItsLockAndUntilLeaving() {
        val s = Settings(locked = mutableSetOf(work))
        val l = locks(s)
        assertTrue(l.categoryLocked(work))
        assertFalse(l.categoryLocked(home))
        assertFalse(l.categoryLocked(null))
        assertFalse(l.tabLocked(incognito = false, category = home))
        l.unlockedCategories += work
        assertFalse(l.tabLocked(incognito = false, category = work))
        l.left()
        assertTrue(l.categoryLocked(work))
    }

    @Test
    fun onExitClosesTheStorageWhenLeaving() {
        val l = locks(Settings(vaultLockOnExit = true))
        l.vaultUnlocked = true
        assertTrue(l.left())
        assertFalse(l.vaultUnlocked)
    }

    @Test
    fun byTimeClosesOnlyAfterTheMinutesAway() {
        val l = locks(Settings(vaultLockOnExit = false, vaultLockMinutes = 5))
        l.vaultUnlocked = true
        assertFalse(l.left())
        clock += 5 * 60_000L
        assertFalse(l.returned())
        assertTrue(l.vaultUnlocked)
        l.left()
        clock += 5 * 60_000L + 1
        assertTrue(l.returned())
        assertFalse(l.vaultUnlocked)
    }

    @Test
    fun aReturnCountsOnceAndOnlyAfterLeaving() {
        val l = locks(Settings(vaultLockOnExit = false, vaultLockMinutes = 1))
        l.vaultUnlocked = true
        clock += 10 * 60_000L
        assertFalse("no return without leaving", l.returned())
        l.left()
        clock += 2 * 60_000L
        assertTrue(l.returned())
        l.vaultUnlocked = true
        clock += 10 * 60_000L
        assertFalse("the same absence is not counted twice", l.returned())
        assertTrue(l.vaultUnlocked)
    }

    @Test
    fun neverKeepsTheStorageOpen() {
        val l = locks(Settings(vaultLockOnExit = false, vaultLockMinutes = null))
        l.vaultUnlocked = true
        assertFalse(l.left())
        clock += 24 * 60 * 60_000L
        assertFalse(l.returned())
        assertTrue(l.vaultUnlocked)
    }
}
