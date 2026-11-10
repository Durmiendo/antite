package dev.antite.browser.engine

import org.json.JSONObject
import java.io.File
import java.io.IOException

// An engine counts only if it was built for this shell's engine API: an older adapter lacks methods the shell calls.
class EngineStore(filesDir: File, val abi: String? = Abi.current, val api: Int = dev.antite.engine.BuildConfig.ENGINE_API) {
    companion object {
        const val READY = "ready"
        const val RESOURCES = "gecko.zip"
        private const val ACTIVE = "active"
        private const val PENDING = "pending"
        private const val PREVIOUS = "previous"
        private const val STARTS = "starts"
        private const val REJECTED = "rejected"
        private const val NOTICE = "notice"
        const val MAX_FAILED_STARTS = 3
        const val ABI = "abi"

        fun abiOf(engine: File): String? = runCatching { File(engine, ABI).readText().trim() }.getOrNull()

        fun apiOf(engine: File): Int? = runCatching { JSONObject(File(engine, "engine.json").readText()).getInt("api") }.getOrNull()
    }

    val dir = File(filesDir, "engines")

    private fun read(name: String): File? {
        val value = runCatching { File(dir, name).readText().trim() }.getOrNull() ?: return null
        if (value.isEmpty() || '/' in value) return null
        return File(dir, value).takeIf { File(it, READY).exists() && abiOf(it) == abi && apiOf(it) == api }
    }

    private fun write(name: String, engine: File) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(engine.name)
        if (!tmp.renameTo(File(dir, name))) throw IOException("cannot write $name")
    }

    fun active(): File? = read(ACTIVE)

    fun pending(): File? = read(PENDING)

    fun previous(): File? = read(PREVIOUS)

    fun activate(engine: File) = write(ACTIVE, engine)

    fun setPending(engine: File) = write(PENDING, engine)

    // Only the main process may call this, before any engine class is loaded.
    fun applyPending(): Boolean {
        val next = pending() ?: return false
        val current = active()
        if (current != null && current != next) write(PREVIOUS, current)
        activate(next)
        File(dir, PENDING).delete()
        return true
    }

    // Every engine start is counted until the browser has run for a while; a run of failed starts means a broken engine.
    fun countStart() {
        val cur = active() ?: return
        val (name, count) = starts()
        File(dir, STARTS).writeText("${cur.name} ${if (name == cur.name) count + 1 else 1}")
    }

    fun markStarted() {
        File(dir, STARTS).delete()
    }

    private fun starts(): Pair<String, Int> {
        val parts = runCatching { File(dir, STARTS).readText().trim().split(' ') }.getOrNull() ?: return "" to 0
        return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun rollbackIfCrashing(): Boolean {
        val cur = active() ?: return false
        val prev = previous() ?: return false
        val (name, count) = starts()
        if (name != cur.name || count < MAX_FAILED_STARTS) return false
        reject(cur)
        activate(prev)
        File(dir, PREVIOUS).delete()
        File(dir, STARTS).delete()
        File(dir, NOTICE).writeText("rollback ${version(cur)} ${version(prev)}")
        return true
    }

    fun reject(engine: File) {
        File(dir, REJECTED).appendText(engine.name + "\n")
    }

    fun rejected(): Set<String> =
        runCatching { File(dir, REJECTED).readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    fun takeNotice(): String? {
        val f = File(dir, NOTICE)
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        f.delete()
        return text
    }

    fun removeUnused() {
        val keep = setOfNotNull(active(), pending(), previous()).map { it.name }
        dir.listFiles { f -> f.isDirectory && !f.name.endsWith(".tmp") && f.name !in keep }.orEmpty()
            .forEach { it.deleteRecursively() }
    }

    fun version(engine: File): String? =
        runCatching { JSONObject(File(engine, "engine.json").readText()).getString("version") }.getOrNull()

    fun dexFiles(engine: File): List<File> = engine.listFiles { f -> f.name.endsWith(".dex") }.orEmpty().sortedBy { it.name }

    fun libDir(engine: File) = File(engine, "lib")

    fun resources(engine: File) = File(engine, RESOURCES)
}
