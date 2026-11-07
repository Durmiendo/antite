package dev.antite.browser;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class Csv {
    private Csv() {
    }

    static List<String> readRow(BufferedReader r) throws IOException {
        int ch = r.read();
        if (ch < 0) return null;
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        while (ch >= 0) {
            char c = (char) ch;
            if (quoted) {
                if (c == '"') {
                    r.mark(1);
                    int next = r.read();
                    if (next == '"') {
                        cell.append('"');
                    } else {
                        quoted = false;
                        if (next >= 0) r.reset();
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                break;
            } else if (c != '\r') {
                cell.append(c);
            }
            ch = r.read();
        }
        out.add(cell.toString());
        return out;
    }

    static String row(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            String s = cells[i] == null ? "" : cells[i];
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
                sb.append('"').append(s.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(s);
            }
        }
        return sb.append('\n').toString();
    }
}
