package dev.antite.browser.l10n

// The subset of Fluent (projectfluent.org) the interface strings use; see docs/decisions/0005-localization.md.
// Pure Kotlin, so JVM tests run it; plural categories come from the caller.
class Fluent(text: String) {
    private companion object {
        val ident = Regex("-?[a-zA-Z][a-zA-Z0-9_-]*")
        val start = Regex("^(-?[a-zA-Z][a-zA-Z0-9_-]*) *=(.*)$")
    }

    sealed interface Part
    class Text(val value: String) : Part
    class Var(val name: String) : Part
    class Ref(val id: String) : Part
    class Select(val selector: String, val variants: List<Pair<String, List<Part>>>, val default: Int) : Part

    val messages = mutableMapOf<String, List<Part>>()
    val errors = mutableListOf<String>()

    init {
        parse(text)
    }

    fun variables(id: String): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(parts: List<Part>) {
            for (p in parts) when (p) {
                is Var -> out += p.name
                is Select -> {
                    out += p.selector
                    p.variants.forEach { walk(it.second) }
                }
                else -> Unit
            }
        }
        messages[id]?.let(::walk)
        return out
    }

    private fun parse(text: String) {
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.startsWith("#")) {
                i++
                continue
            }
            val m = start.find(line)
            if (m == null) {
                errors += "line ${i + 1}: expected 'id = value': $line"
                i++
                continue
            }
            val id = m.groupValues[1]
            val body = mutableListOf(m.groupValues[2])
            i++
            while (i < lines.size && (lines[i].isEmpty() || lines[i][0] == ' ' || lines[i][0] == '}')) body += lines[i++]
            while (body.size > 1 && body.last().isBlank()) body.removeAt(body.lastIndex)
            if (body.any { it.trimStart().startsWith(".") }) errors += "$id: attributes are not supported"
            val value = dedent(body)
            if (id in messages) errors += "$id: defined twice"
            try {
                val (parts, end) = pattern(value, 0, false)
                if (end != value.length) errors += "$id: unexpected '${value[end]}'"
                messages[id] = parts
            } catch (e: IllegalArgumentException) {
                errors += "$id: ${e.message}"
            }
        }
    }

    // The first line keeps its text after '='; continuation lines lose their common indent.
    private fun dedent(body: List<String>): String {
        val first = body[0].trimStart()
        val rest = body.drop(1)
        val indent = rest.filter { it.isNotBlank() }.minOfOrNull { l -> l.length - l.trimStart().length } ?: 0
        val tail = rest.map { if (it.isBlank()) "" else it.substring(indent) }
        return (if (first.isEmpty()) tail else listOf(first) + tail).joinToString("\n")
    }

    // In a select variant the pattern ends where a line starts the next variant or closes the select.
    private fun pattern(s: String, from: Int, inVariant: Boolean): Pair<List<Part>, Int> {
        val parts = mutableListOf<Part>()
        val text = StringBuilder()
        var i = from
        fun flush() {
            if (text.isNotEmpty()) parts += Text(text.toString())
            text.clear()
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '\n' && inVariant) {
                var j = i + 1
                while (j < s.length && s[j] == ' ') j++
                if (j >= s.length || s[j] == '[' || s[j] == '*' || s[j] == '}') break
            }
            if (c == '}') {
                if (inVariant) break
                throw IllegalArgumentException("unbalanced '}'")
            }
            if (c == '{') {
                flush()
                val (part, end) = placeable(s, i + 1)
                parts += part
                i = end
                continue
            }
            text.append(c)
            i++
        }
        flush()
        return trim(parts) to i
    }

    private fun trim(parts: MutableList<Part>): List<Part> {
        (parts.firstOrNull() as? Text)?.let { parts[0] = Text(it.value.trimStart()) }
        (parts.lastOrNull() as? Text)?.let { parts[parts.lastIndex] = Text(it.value.trimEnd()) }
        return parts.filter { it !is Text || it.value.isNotEmpty() }
    }

    private fun skip(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    // From just after '{' to just after the matching '}'.
    private fun placeable(s: String, from: Int): Pair<Part, Int> {
        var i = skip(s, from)
        val expr: Part
        when {
            i < s.length && s[i] == '"' -> {
                val end = s.indexOf('"', i + 1)
                require(end > 0) { "unterminated string literal" }
                expr = Text(s.substring(i + 1, end))
                i = end + 1
            }
            i < s.length && s[i] == '$' -> {
                val m = ident.matchAt(s, i + 1) ?: throw IllegalArgumentException("bad variable name")
                expr = Var(m.value)
                i = m.range.last + 1
            }
            else -> {
                val m = ident.matchAt(s, i) ?: throw IllegalArgumentException("bad placeable")
                require(i + m.value.length >= s.length || s[i + m.value.length] != '(') { "functions are not supported" }
                expr = Ref(m.value)
                i = m.range.last + 1
            }
        }
        i = skip(s, i)
        if (s.startsWith("->", i)) {
            val selector = (expr as? Var)?.name ?: throw IllegalArgumentException("only a variable can be selected on")
            val variants = mutableListOf<Pair<String, List<Part>>>()
            var default = -1
            i = skip(s, i + 2)
            while (i < s.length && s[i] != '}') {
                if (s[i] == '*') {
                    require(default < 0) { "two default variants" }
                    default = variants.size
                    i++
                }
                require(i < s.length && s[i] == '[') { "expected a variant key" }
                val close = s.indexOf(']', i)
                require(close > 0) { "unterminated variant key" }
                val key = s.substring(i + 1, close).trim()
                val (parts, end) = pattern(s, close + 1, true)
                variants += key to parts
                i = skip(s, end)
            }
            require(default >= 0) { "select without a default variant" }
            require(i < s.length) { "unterminated select" }
            return Select(selector, variants, default) to i + 1
        }
        require(i < s.length && s[i] == '}') { "expected '}'" }
        return expr to i + 1
    }

    fun format(id: String, args: Map<String, Any?>, plural: (Number) -> String, lookup: (String) -> List<Part>?): String? {
        val parts = messages[id] ?: return null
        val out = StringBuilder()
        write(parts, args, plural, lookup, out, 0)
        return out.toString()
    }

    private fun write(
        parts: List<Part>,
        args: Map<String, Any?>,
        plural: (Number) -> String,
        lookup: (String) -> List<Part>?,
        out: StringBuilder,
        depth: Int,
    ) {
        for (p in parts) when (p) {
            is Text -> out.append(p.value)
            is Var -> out.append(args[p.name] ?: "{\$${p.name}}")
            is Ref -> {
                val target = if (depth < 8) lookup(p.id) else null
                if (target == null) out.append("{${p.id}}") else write(target, args, plural, lookup, out, depth + 1)
            }
            is Select -> {
                val value = args[p.selector]
                val chosen = p.variants.indexOfFirst { (key, _) ->
                    when (value) {
                        is Number -> key.toDoubleOrNull()?.let { it == value.toDouble() } ?: false
                        null -> false
                        else -> key == value.toString()
                    }
                }.takeIf { it >= 0 }
                    ?: (value as? Number)?.let { n -> plural(n).let { cat -> p.variants.indexOfFirst { it.first == cat } } }?.takeIf { it >= 0 }
                    ?: p.default
                write(p.variants[chosen].second, args, plural, lookup, out, depth)
            }
        }
    }
}
