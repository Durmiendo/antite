package dev.antite.browser.engine

import org.json.JSONObject
import java.io.File
import java.io.IOException

class EngineStore(filesDir: File) {
    companion object {
        const val READY = "ready"
        const val RESOURCES = "gecko.zip"
        private const val ACTIVE = "active"
        private const val PENDING = "pending"
        private const val PREVIOUS = "previous"
    }

    val dir = File(filesDir, "engines")

    private fun read(name: String): File? {
        val value = runCatching { File(dir, name).readText().trim() }.getOrNull() ?: return null
        if (value.isEmpty() || '/' in value) return null
        return File(dir, value).takeIf { File(it, READY).exists() }
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
