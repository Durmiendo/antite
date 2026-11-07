package dev.antite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;

public class CsvTest {
    private static BufferedReader reader(String s) {
        return new BufferedReader(new StringReader(s));
    }

    @Test
    public void readsChromeExport() throws Exception {
        BufferedReader r = reader("name,url,username,password,note\r\n"
                + "example.com,https://example.com/,user,\"p,a\"\"ss\",\r\n");
        assertEquals(Arrays.asList("name", "url", "username", "password", "note"), Csv.readRow(r));
        assertEquals(Arrays.asList("example.com", "https://example.com/", "user", "p,a\"ss", ""), Csv.readRow(r));
        assertNull(Csv.readRow(r));
    }

    @Test
    public void keepsNewlineInsideQuotes() throws Exception {
        BufferedReader r = reader("a,\"line1\nline2\",c\n");
        assertEquals(Arrays.asList("a", "line1\nline2", "c"), Csv.readRow(r));
    }

    @Test
    public void lastRowWithoutNewline() throws Exception {
        BufferedReader r = reader("x,пароль");
        assertEquals(Arrays.asList("x", "пароль"), Csv.readRow(r));
        assertNull(Csv.readRow(r));
    }

    @Test
    public void rowRoundTrips() throws Exception {
        String[] cells = {"host", "https://h/", "u,ser", "pa\"ss\nword", ""};
        List<String> back = Csv.readRow(reader(Csv.row(cells)));
        assertEquals(Arrays.asList(cells), back);
    }
}
