package dev.antite.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.antite.browser.ui.Palette
import dev.antite.engine.LoadError

object ErrorPage {
    const val TITLE = "Не удалось открыть страницу"

    private fun reason(error: LoadError, host: String) = when (error) {
        LoadError.HOST_NOT_FOUND -> "Не найден сервер $host. Проверьте адрес и подключение к интернету."
        LoadError.CONNECTION -> "Сервер $host отклонил подключение."
        LoadError.TIMEOUT -> "Сервер $host слишком долго не отвечает."
        LoadError.OFFLINE -> "Нет подключения к интернету."
        LoadError.SECURITY -> "Не удалось установить безопасное подключение к $host. Сертификат сайта не прошёл проверку."
        LoadError.BLOCKED -> "Сайт $host заблокирован как опасный."
        LoadError.OTHER -> "Произошла ошибка при загрузке $host."
    }

    private fun css(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(url: String, error: LoadError, p: Palette): String {
        val host = escape(Urls.host(url).ifEmpty { url })
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$TITLE</title>
<style>
body{margin:0;padding:48px 24px;background:${css(p.background)};color:${css(p.text)};font:16px/1.5 sans-serif}
h1{font-size:22px;font-weight:500;margin:0 0 12px}
p{color:${css(p.textSecondary)};margin:0 0 8px;word-break:break-word}
button{margin-top:24px;padding:10px 24px;border:0;border-radius:20px;background:${css(p.accent)};color:${css(p.background)};font-size:15px}
</style></head><body>
<h1>$TITLE</h1>
<p>${reason(error, host)}</p>
<p>${escape(url)}</p>
<button onclick="location.reload()">Повторить</button>
</body></html>"""
    }
}
