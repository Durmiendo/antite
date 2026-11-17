package dev.antite.arch

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

// The rules of decision 0015 and the plan of 18.11.2026 that need no tool: logic and interface stay apart, files stay
// small, files do not pull in half the project, packages do not depend on each other in a circle. A breach already
// known is listed in baseline.txt; a number there is a ceiling the file may not pass. A new breach fails, and so does
// a listed one that is gone: the list only shrinks.
class ArchTest {
    private val repo = File(System.getProperty("antite.repo") ?: "..")
    private val app = "app/src/main/kotlin/dev/antite/browser"

    private val maxLines = 400
    private val maxProjectImports = 15

    private val logicDirs = listOf("keyring/src/main", "engine-api/src/main", "$app/data")
    private val uiDirs = listOf("$app/ui")
    private val uiImports = listOf("androidx.compose.", "android.view.", "android.widget.", "androidx.activity.", "android.app.Activity")
    private val storageImports = listOf("dev.antite.keys.", "java.io.File", "java.nio.file.", "android.database.")

    private class Source(val path: String, val text: String) {
        val lines = text.lines().size.let { if (text.endsWith("\n")) it - 1 else it }
        val pkg = Regex("^package (\\S+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: ""
        val imports = Regex("^import (\\S+)", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
    }

    private fun sources(vararg dirs: String, ext: String = "kt"): List<Source> = dirs.flatMap { d ->
        File(repo, d).walkTopDown().filter { it.isFile && it.extension == ext }
            .map { Source(it.relativeTo(repo).path, it.readText()) }.toList()
    }

    private fun under(s: Source, dirs: List<String>) = dirs.any { s.path.startsWith("$it/") }

    private fun breaches(): Map<String, Int?> {
        val out = HashMap<String, Int?>()
        val kotlin = sources("app/src/main", "keyring/src/main", "engine-api/src/main", "engine-gecko/src/main")
        for (s in kotlin + sources("keycore/src", ext = "c")) if (s.lines > maxLines) out["size ${s.path}"] = s.lines
        for (s in kotlin) {
            if (under(s, logicDirs)) s.imports.filter { i -> uiImports.any { i.startsWith(it) } }.forEach { out["logic-ui ${s.path} $it"] = null }
            if (under(s, uiDirs)) s.imports.filter { i -> storageImports.any { i.startsWith(it) } }.forEach { out["ui-storage ${s.path} $it"] = null }
            val project = s.imports.filter { it.startsWith("dev.antite.") && it.substringBeforeLast('.') != s.pkg }.toSet().size
            if (project > maxProjectImports) out["fanout ${s.path}"] = project
        }
        val edges = HashMap<String, MutableSet<String>>()
        for (s in kotlin.filter { it.pkg.startsWith("dev.antite.browser") }) {
            for (i in s.imports) {
                val m = Regex("^(dev\\.antite\\.browser(?:\\.[a-z0-9]+)*)\\.[A-Z]").find(i) ?: continue
                val target = m.groupValues[1]
                if (target != s.pkg) edges.getOrPut(s.pkg) { HashSet() } += target
            }
        }
        for ((a, targets) in edges) for (b in targets) if (a < b && edges[b]?.contains(a) == true) out["cycle $a $b"] = null
        return out
    }

    private fun baseline(): Map<String, Int?> = File(repo, "archcheck/baseline.txt").readLines()
        .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        .associate { line ->
            val last = line.substringAfterLast(' ')
            if (last.toIntOrNull() != null) line.substringBeforeLast(' ') to last.toInt() else line to null
        }

    @Test
    fun theRulesHold() {
        val found = breaches()
        val known = baseline()
        val problems = ArrayList<String>()
        for ((k, v) in found) {
            if (k !in known) problems += "new: $k${v?.let { " ($it)" } ?: ""}"
            else {
                val ceiling = known[k]
                if (ceiling != null && v != null && v > ceiling) problems += "grew: $k $v, ceiling $ceiling"
            }
        }
        for (k in known.keys) if (k !in found) problems += "fixed, take it out of baseline.txt: $k"
        for ((k, v) in known) if (v != null && found[k] != null && found[k]!! < v) problems += "smaller now, lower the ceiling: $k ${found[k]}"
        if (problems.isNotEmpty()) fail(problems.sorted().joinToString("\n"))
    }
}
