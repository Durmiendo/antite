package dev.antite.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomValuesTest {
    @Test
    fun numbersStayInTheirRange() {
        val n = CustomValues.number(1..50)
        assertEquals(7, n(" 7 "))
        assertEquals(50, n("50"))
        assertNull(n("0"))
        assertNull(n("51"))
        assertNull(n("seven"))
        assertNull(n(""))
    }

    @Test
    fun fontScaleIsAPercent() {
        assertEquals(1.25f, CustomValues.fontScale("125")!!, 0f)
        assertEquals(0.5f, CustomValues.fontScale("50 %")!!, 0f)
        assertNull(CustomValues.fontScale("49"))
        assertNull(CustomValues.fontScale("301"))
    }

    @Test
    fun searchAddressMustBeHttpsWithAHost() {
        assertEquals("https://search.example/?q=%s", CustomValues.searchUrl(" https://search.example/?q=%s "))
        assertEquals("https://search.example/find?q=", CustomValues.searchUrl("https://search.example/find?q="))
        assertNull(CustomValues.searchUrl("http://search.example/?q=%s"))
        assertNull(CustomValues.searchUrl("javascript:alert(%s)"))
        assertNull(CustomValues.searchUrl("https:///?q=%s"))
        assertNull(CustomValues.searchUrl("https://search example/?q="))
    }

    @Test
    fun queryGoesInPlaceOfThePlaceholderOrAtTheEnd() {
        assertEquals("https://s.example/?q=a%20b&x=1", CustomValues.search("https://s.example/?q=%s&x=1", "a%20b"))
        assertEquals("https://s.example/?q=a%20b", CustomValues.search("https://s.example/?q=", "a%20b"))
    }

    @Test
    fun dohAddressIsHttpsWithoutAPlaceholder() {
        assertEquals("https://dns.example/dns-query", CustomValues.dohUrl("https://dns.example/dns-query"))
        assertNull(CustomValues.dohUrl("https://dns.example/?q=%s"))
        assertNull(CustomValues.dohUrl("tls://dns.example"))
        assertNull(CustomValues.dohUrl("https://localhost/dns-query"))
    }
}
