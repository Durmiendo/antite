package dev.antite.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlsTest {
    @Test
    fun hostEndsBeforePathAndAfterPort() {
        val d = Urls.display("https://a.b.c.example.com:8080/x/y?q=1")
        assertEquals("https://", d.scheme)
        assertEquals("a.b.c.example.com:8080/x/y?q=1", d.text)
        assertEquals("a.b.c.example.com:8080".length, d.hostEnd)
    }

    @Test
    fun userInfoIsNotShown() {
        assertEquals("example.com/", Urls.display("https://user:pw@example.com/").text)
    }

    @Test
    fun punycodeHostIsShownInUnicode() {
        assertEquals("пример.испытание", Urls.displayHost("xn--e1afmkfd.xn--80akhbyknj4f"))
        assertEquals("bücher.example", Urls.displayHost("xn--bcher-kva.example"))
    }

    @Test
    fun lookalikeHostsStayInPunycode() {
        // "ехамрӏе" in Cyrillic looks like example.
        val fake = java.net.IDN.toASCII("ехамрӏе", java.net.IDN.ALLOW_UNASSIGNED) + ".example"
        assertEquals(fake, Urls.displayHost(fake))
        // Latin and Cyrillic in one label.
        val mixed = java.net.IDN.toASCII("exаmple") + ".example"
        assertEquals(mixed, Urls.displayHost(mixed))
        // A Cyrillic word that cannot pass for Latin is fine under a Latin TLD.
        assertEquals("пример.example", Urls.displayHost(java.net.IDN.toASCII("пример") + ".example"))
    }

    @Test
    fun escapedUtf8IsDecodedButNotAscii() {
        assertEquals("/вики/a%20b%2F", Urls.unescape("/%D0%B2%D0%B8%D0%BA%D0%B8/a%20b%2F"))
        assertEquals("/%E2%80%AE", Urls.unescape("/%E2%80%AE"))
        assertEquals("/%D0", Urls.unescape("/%D0"))
    }

    @Test
    fun typedAddressesThatCannotRunInThePageOrReadFiles() {
        assertEquals(true, Urls.canOpenTyped("https://example.com/"))
        assertEquals(true, Urls.canOpenTyped("about:config"))
        assertEquals(true, Urls.canOpenTyped("view-source:https://example.com/"))
        assertEquals(false, Urls.canOpenTyped("javascript://%0Aalert(1)"))
        assertEquals(false, Urls.canOpenTyped("JavaScript:alert(1)"))
        assertEquals(false, Urls.canOpenTyped("file:///data/data/dev.antite.browser/databases/antite.db"))
        assertEquals(false, Urls.canOpenTyped("view-source:file:///sdcard/"))
        assertEquals(false, Urls.canOpenTyped("resource://android/assets/"))
    }
}
