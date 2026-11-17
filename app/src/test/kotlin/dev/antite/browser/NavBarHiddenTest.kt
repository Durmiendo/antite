package dev.antite.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBarHiddenTest {
    private val height = 100f
    private val dead = 30f

    @Test
    fun hidesOnlyWithTheToolbarFullyHiddenAndTheSettingOn() {
        assertTrue(navBarHidden(100f, height, dead, wasHidden = false, hideBar = true))
        assertFalse(navBarHidden(100f, height, dead, wasHidden = false, hideBar = false))
        assertFalse(navBarHidden(99f, height, dead, wasHidden = false, hideBar = true))
    }

    @Test
    fun comesBackOnlyPastTheDeadZone() {
        assertTrue(navBarHidden(80f, height, dead, wasHidden = true, hideBar = true))
        assertTrue(navBarHidden(71f, height, dead, wasHidden = true, hideBar = true))
        assertFalse(navBarHidden(70f, height, dead, wasHidden = true, hideBar = true))
    }

    @Test
    fun noDeadZoneMeansAnyMoveBringsItBack() {
        assertFalse(navBarHidden(99f, height, 0f, wasHidden = true, hideBar = true))
    }
}
