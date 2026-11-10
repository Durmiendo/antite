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
        assertEquals("пример.рф", Urls.displayHost("xn--e1afmkfd.xn--p1ai"))
        assertEquals("münchen.de", Urls.displayHost("xn--mnchen-3ya.de"))
    }

    @Test
    fun lookalikeHostsStayInPunycode() {
        // "аррӏе" in Cyrillic looks like apple.
        val fake = java.net.IDN.toASCII("аррӏе", java.net.IDN.ALLOW_UNASSIGNED) + ".com"
        assertEquals(fake, Urls.displayHost(fake))
        // Latin and Cyrillic in one label.
        val mixed = java.net.IDN.toASCII("pаypal") + ".com"
        assertEquals(mixed, Urls.displayHost(mixed))
        // A Cyrillic word that cannot pass for Latin is fine under a Latin TLD.
        assertEquals("яндекс.com", Urls.displayHost(java.net.IDN.toASCII("яндекс") + ".com"))
    }

    @Test
    fun escapedUtf8IsDecodedButNotAscii() {
        assertEquals("/вики/a%20b%2F", Urls.unescape("/%D0%B2%D0%B8%D0%BA%D0%B8/a%20b%2F"))
        assertEquals("/%E2%80%AE", Urls.unescape("/%E2%80%AE"))
        assertEquals("/%D0", Urls.unescape("/%D0"))
    }
}
