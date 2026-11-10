package dev.antite.browser

import android.net.Uri
import java.util.Locale

object Urls {
    fun isWeb(url: String?) = url != null && (url.startsWith("http://") || url.startsWith("https://"))

    fun isBlank(url: String?) = url.isNullOrEmpty() || url == "about:blank"

    fun host(url: String?): String {
        val h = url?.let { Uri.parse(it).host } ?: return url ?: ""
        return h.removePrefix("www.")
    }

    fun siteHost(url: String?): String = (url?.let { Uri.parse(it).host } ?: "").lowercase(Locale.ROOT)

    fun hostOfInput(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        return siteHost(if ("://" in s) s else "https://$s")
    }

    // How the address bar shows a URL: the host in Unicode unless that could pass for another site, escaped UTF-8 in
    // the rest decoded. hostEnd is where the host ends in text, so a long host can be scrolled to show its end.
    class Display(val scheme: String, val text: String, val hostEnd: Int)

    fun display(url: String): Display {
        val sep = url.indexOf("://")
        if (sep <= 0) return Display("", url, 0)
        val scheme = url.substring(0, sep + 3)
        val rest = url.substring(sep + 3)
        val authEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authEnd).substringAfterLast('@')
        val portAt = authority.lastIndexOf(':').takeIf { it > authority.lastIndexOf(']') } ?: -1
        val host = if (portAt >= 0) authority.substring(0, portAt) else authority
        val port = if (portAt >= 0) authority.substring(portAt) else ""
        val shown = displayHost(host)
        val text = shown + port + unescape(rest.substring(authEnd))
        return Display(scheme, text, shown.length + port.length)
    }

    fun displayHost(host: String): String {
        val labels = host.split('.')
        if (labels.none { it.startsWith("xn--", ignoreCase = true) }) return host
        val decoded = labels.map { l ->
            if (!l.startsWith("xn--", ignoreCase = true)) l
            else runCatching { java.net.IDN.toUnicode(l, java.net.IDN.ALLOW_UNASSIGNED) }.getOrDefault(l)
        }
        val tldCyrillic = decoded.last().any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }
        return labels.indices.joinToString(".") { i -> if (safeLabel(decoded[i], tldCyrillic)) decoded[i] else labels[i] }
    }

    // Like Chrome's IDN spoof checks, simplified: one script per label, and a Cyrillic label made only of letters that
    // look Latin stays in punycode unless the top-level domain is Cyrillic too.
    private const val LATIN_LOOKALIKES = "аеорсухіјѕԁԛԝһӏкмнтвь"

    private fun safeLabel(label: String, tldCyrillic: Boolean): Boolean {
        val scripts = label.codePoints().toArray().map { Character.UnicodeScript.of(it) }
            .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }.toSet()
        if (scripts.size > 1) return false
        if (scripts.singleOrNull() == Character.UnicodeScript.CYRILLIC && !tldCyrillic) {
            return label.any { it.isLetter() && it.lowercaseChar() !in LATIN_LOOKALIKES }
        }
        return true
    }

    // Escaped non-ASCII text is decoded; escaped ASCII (spaces, slashes) and anything invisible stays escaped.
    fun unescape(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                val bytes = java.io.ByteArrayOutputStream()
                var j = i
                while (j + 2 < s.length && s[j] == '%') {
                    val b = s.substring(j + 1, j + 3).toIntOrNull(16) ?: break
                    if (b < 0x80) break
                    bytes.write(b)
                    j += 3
                }
                val text = if (bytes.size() > 0) utf8(bytes.toByteArray()) else null
                if (text != null && text.none { invisible(it) }) {
                    out.append(text)
                    i = j
                    continue
                }
            }
            out.append(s[i])
            i++
        }
        return out.toString()
    }

    private fun utf8(b: ByteArray): String? = runCatching {
        java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
    }.getOrNull()

    private fun invisible(c: Char) = c.isWhitespace() || Character.getType(c).let {
        it == Character.FORMAT.toInt() || it == Character.CONTROL.toInt() || it == Character.UNASSIGNED.toInt()
    }

    fun langName(code: String): String {
        val n = Locale.forLanguageTag(code).getDisplayName(Locale.forLanguageTag(dev.antite.browser.l10n.L10n.code))
        if (n.isEmpty()) return code
        return n.substring(0, 1).uppercase(Locale.ROOT) + n.substring(1)
    }
}
