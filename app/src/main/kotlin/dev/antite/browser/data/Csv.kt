package dev.antite.browser.data

import java.io.BufferedReader

object Csv {
    fun readRow(r: BufferedReader): List<String>? {
        var ch = r.read()
        if (ch < 0) return null
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        while (ch >= 0) {
            val c = ch.toChar()
            if (quoted) {
                if (c == '"') {
                    r.mark(1)
                    val next = r.read()
                    if (next == '"'.code) {
                        cell.append('"')
                    } else {
                        quoted = false
                        if (next >= 0) r.reset()
                    }
                } else {
                    cell.append(c)
                }
            } else when (c) {
                '"' -> quoted = true
                ',' -> {
                    out += cell.toString()
                    cell.setLength(0)
                }
                '\n' -> break
                '\r' -> {}
                else -> cell.append(c)
            }
            ch = r.read()
        }
        out += cell.toString()
        return out
    }

    fun row(vararg cells: String?): String = cells.joinToString(",", postfix = "\n") { raw ->
        val s = raw ?: ""
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
