package dev.antite.browser.data

import dev.antite.browser.testing.MapPrefs
import dev.antite.engine.BackgroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {
    private val list = listOf(Option("a", 10), Option("b", 20), Option("c", 30))

    @Test
    fun aChoiceReadsItsOptionOrItsOwnValue() {
        val p = Prefs(MapPrefs()) { false }
        assertEquals(20, p.pick("k", list, 1, CustomValues.number(1..99)))
        p.edit { putInt("k", 2) }
        assertEquals(30, p.pick("k", list, 1, CustomValues.number(1..99)))
        p.edit { putInt("k", list.size); putString("k.custom", "42") }
        assertEquals("42", p.custom("k", list.size))
        assertEquals(42, p.pick("k", list, 1, CustomValues.number(1..99)))
    }

    @Test
    fun aBrokenOwnValueFallsBackToTheList() {
        val p = Prefs(MapPrefs(mutableMapOf("k" to 3, "k.custom" to "500"))) { false }
        assertEquals(30, p.pick("k", list, 0, CustomValues.number(1..99)))
        val q = Prefs(MapPrefs(mutableMapOf("k" to 1, "k.custom" to "42"))) { false }
        assertNull("an own value counts only while it is the one chosen", q.custom("k", list.size))
    }

    @Test
    fun oldKeysMoveToTheirNewPlaces() {
        val m = MapPrefs(mutableMapOf("js" to false, "autoplay" to 2, "safeBrowsing" to true, "dark" to false))
        Prefs(m) { false }
        assertEquals(1, m.map["cat_js"])
        assertEquals(2, m.map["cat_autoplay"])
        assertEquals(true, m.map["bg." + BackgroundService.SAFE_BROWSING])
        assertEquals(1, m.map["theme"])
        assertFalse(listOf("js", "autoplay", "safeBrowsing", "dark").any { it in m.map })
    }

    @Test
    fun theSystemThemeIsAsked() {
        assertTrue(Prefs(MapPrefs(mutableMapOf("theme" to 0))) { true }.isDark())
        assertFalse(Prefs(MapPrefs(mutableMapOf("theme" to 0))) { false }.isDark())
        assertTrue(Prefs(MapPrefs(mutableMapOf("theme" to 2))) { false }.isDark())
    }

    @Test
    fun secureDnsIsOffOrAnAddress() {
        assertNull(Prefs(MapPrefs()) { false }.dohUrl)
        assertEquals(Options.doh[1].value, Prefs(MapPrefs(mutableMapOf("doh" to 1))) { false }.dohUrl)
        val own = Prefs(MapPrefs(mutableMapOf("doh" to Options.doh.size, "doh.custom" to "https://dns.example/q"))) { false }
        assertEquals("https://dns.example/q", own.dohUrl)
    }
}
