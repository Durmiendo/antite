package dev.antite.browser

import dev.antite.browser.ui.DarkPalette
import dev.antite.engine.LoadError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPageTest {
    @Test
    fun theAddressCannotBreakOutOfTheText() {
        val url = "https://example.com/\"><script>alert(1)</script>&x"
        val html = ErrorPage.html(url, LoadError.CONNECTION, DarkPalette, host = "example.com")
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertFalse(html.contains("\"><script"))
        assertTrue(html.contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;&amp;x"))
        assertTrue(html.contains("location.reload()"))
    }

    @Test
    fun onlyTheHttpsOnlyPageOffersToGoOn() {
        val insecure = ErrorPage.html("http://example.com/", LoadError.HTTPS_ONLY, DarkPalette, "example.com")
        assertTrue(insecure.contains("reloadWithHttpsOnlyException"))
        for (e in LoadError.entries.filter { it != LoadError.HTTPS_ONLY }) {
            assertFalse(e.name, ErrorPage.html("https://example.com/", e, DarkPalette, "example.com").contains("reloadWithHttpsOnlyException"))
        }
    }
}
