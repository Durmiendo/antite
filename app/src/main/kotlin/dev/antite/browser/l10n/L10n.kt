package dev.antite.browser.l10n

import android.content.Context
import android.icu.text.PluralRules
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import java.util.Locale

// The interface language: the chosen one, then English, then Russian for messages a translation lacks.
object L10n {
    const val PREF = "uiLanguage"

    // Code and its name in that language; "" follows the system.
    val languages = listOf("ru" to "Русский", "en" to "English")

    private class Bundle(val code: String, val fluent: Fluent, val plural: PluralRules)

    @Volatile
    private var chain: List<Bundle> = emptyList()

    var code: String = "ru"
        private set

    // Bumped on a language change. format reads it, so Compose redraws every place that showed a string.
    val revision = mutableIntStateOf(0)

    fun init(context: Context) {
        val chosen = context.getSharedPreferences("antite", Context.MODE_PRIVATE).getString(PREF, "") ?: ""
        val system = Locale.getDefault().language
        val first = chosen.ifEmpty { if (languages.any { it.first == system }) system else "en" }
        code = first
        chain = listOf(first, "en", "ru").distinct().map { load(context, it) }
        revision.intValue++
    }

    private fun load(context: Context, code: String): Bundle {
        val text = context.assets.list("l10n/$code").orEmpty().sorted().filter { it.endsWith(".ftl") }
            .joinToString("\n") { context.assets.open("l10n/$code/$it").bufferedReader().use { r -> r.readText() } }
        val fluent = Fluent(text)
        fluent.errors.forEach { Log.w("Antite", "l10n $code: $it") }
        return Bundle(code, fluent, PluralRules.forLocale(Locale.forLanguageTag(code)))
    }

    fun format(id: String, args: Map<String, Any?> = emptyMap()): String {
        revision.intValue
        for (b in chain) {
            val lookup = { ref: String -> b.fluent.messages[ref] ?: chain.firstNotNullOfOrNull { it.fluent.messages[ref] } }
            b.fluent.format(id, args, { n -> b.plural.select(n.toDouble()) }, lookup)?.let { return it }
        }
        return id
    }
}
