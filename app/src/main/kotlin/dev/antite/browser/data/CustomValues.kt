package dev.antite.browser.data

import java.net.URI

// The user's own value of a choice, from the text typed for it: null when the text is not a valid one.
object CustomValues {
    fun number(range: IntRange): (String) -> Int? = { s -> s.trim().toIntOrNull()?.takeIf { it in range } }

    // Percent of the normal text size.
    val fontScale: (String) -> Float? = { s -> number(50..300)(s.trim().removeSuffix("%").trim())?.let { it / 100f } }

    // The query goes in place of %s, or at the end of an address without one.
    val searchUrl: (String) -> String? = { s -> s.trim().takeIf { https(it.replace("%s", "q")) } }

    val dohUrl: (String) -> String? = { s -> s.trim().takeIf { "%s" !in it && https(it) } }

    fun search(template: String, encodedQuery: String) =
        if ("%s" in template) template.replace("%s", encodedQuery) else template + encodedQuery

    private fun https(s: String): Boolean {
        if (!s.startsWith("https://") || s.any { it.isWhitespace() }) return false
        val host = runCatching { URI(s).host }.getOrNull()
        return !host.isNullOrEmpty() && '.' in host
    }
}
