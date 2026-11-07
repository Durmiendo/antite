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

    fun langName(code: String): String {
        val n = Locale.forLanguageTag(code).getDisplayName(Locale.forLanguageTag("ru"))
        if (n.isEmpty()) return code
        return n.substring(0, 1).uppercase(Locale.ROOT) + n.substring(1)
    }
}
