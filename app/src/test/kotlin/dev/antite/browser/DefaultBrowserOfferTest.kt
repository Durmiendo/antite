package dev.antite.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultBrowserOfferTest {
    @Test
    fun offeredOnlyWhenItCanBecomeDefaultAndIsNotYet() {
        assertTrue(DefaultBrowserOffer.due(never = false, laterUntil = 0, now = 10, canBe = true, isDefault = false))
        assertFalse(DefaultBrowserOffer.due(never = false, laterUntil = 0, now = 10, canBe = false, isDefault = false))
        assertFalse(DefaultBrowserOffer.due(never = false, laterUntil = 0, now = 10, canBe = true, isDefault = true))
    }

    @Test
    fun noMeansNeverAndLaterWaits() {
        assertFalse(DefaultBrowserOffer.due(never = true, laterUntil = 0, now = 10, canBe = true, isDefault = false))
        assertFalse(DefaultBrowserOffer.due(never = false, laterUntil = 11, now = 10, canBe = true, isDefault = false))
        assertTrue(DefaultBrowserOffer.due(never = false, laterUntil = 10, now = 10, canBe = true, isDefault = false))
    }
}
