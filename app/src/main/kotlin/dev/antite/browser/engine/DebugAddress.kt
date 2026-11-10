package dev.antite.browser.engine

import java.net.URI
import java.net.URISyntaxException

// What "Install from an address" accepts: a full engines.json address, or just the computer running
// tools/publish/serve_local.py (192.168.1.5, 192.168.1.5:8790).
object DebugAddress {
    const val DEFAULT_PORT = 8790

    fun indexUrl(input: String): String {
        val s = input.trim()
        require(s.isNotEmpty()) { "empty address" }
        val hasScheme = "://" in s
        val uri = try {
            URI(if (hasScheme) s else "http://$s")
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException(e.message, e)
        }
        val host = uri.host ?: throw IllegalArgumentException("no host in $s")
        val port = if (uri.port == -1 && !hasScheme) DEFAULT_PORT else uri.port
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() || it == "/" } ?: "/engines.json"
        return "${uri.scheme}://$host${if (port == -1) "" else ":$port"}$path${uri.rawQuery?.let { "?$it" } ?: ""}"
    }

    fun port(url: String): Int = URI(url).let { if (it.port != -1) it.port else if (it.scheme == "https") 443 else 80 }

    fun hostPort(url: String): String = "${URI(url).host}:${port(url)}"
}
