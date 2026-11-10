package dev.antite.browser.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FluentTest {
    // CLDR rules for Russian integers; the app takes them from ICU.
    private fun ru(n: Number): String {
        val i = n.toLong()
        return when {
            i % 10 == 1L && i % 100 != 11L -> "one"
            i % 10 in 2..4 && i % 100 !in 12..14 -> "few"
            else -> "many"
        }
    }

    private fun Fluent.f(id: String, vararg args: Pair<String, Any?>) =
        format(id, mapOf(*args), ::ru) { messages[it] }

    @Test
    fun messagesVariablesTermsAndPlurals() {
        val f = Fluent(
            """
            # comment
            -brand = Antite
            hello = Привет, { ${'$'}name }!
            about = О браузере { -brand }
            tabs = { ${'$'}count ->
                [0] Нет вкладок
                [one] { ${'$'}count } вкладка
                [few] { ${'$'}count } вкладки
               *[many] { ${'$'}count } вкладок
            }
            multi =
                Первая строка
                вторая строка
            braces = { "{" }x{ "}" }
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), f.errors)
        assertEquals("Привет, Аня!", f.f("hello", "name" to "Аня"))
        assertEquals("О браузере Antite", f.f("about"))
        assertEquals("Нет вкладок", f.f("tabs", "count" to 0))
        assertEquals("1 вкладка", f.f("tabs", "count" to 1))
        assertEquals("3 вкладки", f.f("tabs", "count" to 3))
        assertEquals("11 вкладок", f.f("tabs", "count" to 11))
        assertEquals("Первая строка\nвторая строка", f.f("multi"))
        assertEquals("{x}", f.f("braces"))
        assertEquals(setOf("count"), f.variables("tabs"))
    }

    @Test
    fun unsupportedSyntaxIsReported() {
        val f = Fluent("a = { NUMBER(${'$'}x) }\nb = x\n    .title = y\nc = { ${'$'}x ->\n [one] a\n}\n")
        assertEquals(3, f.errors.size)
    }

    @Test
    fun everyLanguageHasTheSameMessagesAndVariables() {
        val root = File("../l10n")
        fun load(code: String) = Fluent(File(root, code).listFiles()!!.filter { it.name.endsWith(".ftl") }
            .sortedBy { it.name }.joinToString("\n") { it.readText() })
        val ru = load("ru")
        assertEquals("ru", emptyList<String>(), ru.errors)
        assertTrue(ru.messages.isNotEmpty())
        for (code in root.list()!!.filter { it != "ru" }) {
            val other = load(code)
            assertEquals(code, emptyList<String>(), other.errors)
            assertEquals("$code: messages", ru.messages.keys.sorted(), other.messages.keys.sorted())
            for (id in ru.messages.keys) {
                val extra = other.variables(id) - ru.variables(id)
                assertTrue("$code/$id uses variables the Russian text has not: $extra", extra.isEmpty())
            }
        }
    }
}
