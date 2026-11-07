package dev.antite.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class CsvTest {
    private fun reader(s: String) = BufferedReader(StringReader(s))

    @Test
    fun readsChromeExport() {
        val r = reader("name,url,username,password,note\r\nexample.com,https://example.com/,user,\"p,a\"\"ss\",\r\n")
        assertEquals(listOf("name", "url", "username", "password", "note"), Csv.readRow(r))
        assertEquals(listOf("example.com", "https://example.com/", "user", "p,a\"ss", ""), Csv.readRow(r))
        assertNull(Csv.readRow(r))
    }

    @Test
    fun keepsNewlineInsideQuotes() {
        assertEquals(listOf("a", "line1\nline2", "c"), Csv.readRow(reader("a,\"line1\nline2\",c\n")))
    }

    @Test
    fun lastRowWithoutNewline() {
        val r = reader("x,пароль")
        assertEquals(listOf("x", "пароль"), Csv.readRow(r))
        assertNull(Csv.readRow(r))
    }

    @Test
    fun rowRoundTrips() {
        val cells = arrayOf("host", "https://h/", "u,ser", "pa\"ss\nword", "")
        assertEquals(cells.toList(), Csv.readRow(reader(Csv.row(*cells))))
    }
}
