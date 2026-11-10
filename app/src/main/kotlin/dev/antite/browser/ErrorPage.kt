package dev.antite.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.antite.browser.l10n.L10n
import dev.antite.browser.l10n.T
import dev.antite.browser.ui.Palette
import dev.antite.engine.LoadError

object ErrorPage {

    private fun reason(error: LoadError, host: String) = when (error) {
        LoadError.HOST_NOT_FOUND -> T.errorHostNotFound(host = host)
        LoadError.CONNECTION -> T.errorConnection(host = host)
        LoadError.TIMEOUT -> T.errorTimeout(host = host)
        LoadError.OFFLINE -> T.errorOffline
        LoadError.SECURITY -> T.errorSecurity(host = host)
        LoadError.BLOCKED -> T.errorBlocked(host = host)
        LoadError.OTHER, LoadError.HTTPS_ONLY -> T.errorOther(host = host)
    }

    private fun css(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // A site without HTTPS under "always use secure connections": like Chrome, the page warns and offers to go on.
    // Gecko lets any GeckoView error page add the per-site exception and reload over HTTP.
    fun html(url: String, error: LoadError, p: Palette): String {
        val host = Urls.host(url).ifEmpty { url }
        val insecure = error == LoadError.HTTPS_ONLY
        val title = escape(if (insecure) T.errorInsecureTitle else T.errorPageTitle)
        val reasonText = if (insecure) T.errorInsecureText(host = host) else reason(error, host)
        val buttons = if (insecure) {
            """<button onclick="history.back()">${escape(T.actionBack)}</button>
<button class="link" onclick="document.reloadWithHttpsOnlyException()">${escape(T.errorInsecureContinue)}</button>"""
        } else {
            """<button onclick="location.reload()">${escape(T.actionRetry)}</button>"""
        }
        return """<!doctype html><html lang="${L10n.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>
body{margin:0;padding:48px 24px;background:${css(p.background)};color:${css(p.text)};font:16px/1.5 sans-serif}
h1{font-size:22px;font-weight:500;margin:0 0 12px}
p{color:${css(p.textSecondary)};margin:0 0 8px;word-break:break-word}
button{margin-top:24px;margin-right:8px;padding:10px 24px;border:0;border-radius:20px;background:${css(p.accent)};color:${css(p.background)};font-size:15px}
button.link{background:none;color:${css(p.accent)}}
</style></head><body>
<h1>$title</h1>
<p>${escape(reasonText)}</p>
<p>${escape(url)}</p>
$buttons
</body></html>"""
    }
}
