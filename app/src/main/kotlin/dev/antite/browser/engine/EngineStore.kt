package dev.antite.browser.engine

import java.io.File
import java.io.IOException

class EngineStore(filesDir: File) {
    companion object {
        const val READY = "ready"
        const val RESOURCES = "gecko.zip"
        private const val ACTIVE = "active"
    }

    val dir = File(filesDir, "engines")

    fun active(): File? {
        val name = runCatching { File(dir, ACTIVE).readText().trim() }.getOrNull() ?: return null
        if (name.isEmpty() || '/' in name) return null
        return File(dir, name).takeIf { File(it, READY).exists() }
    }

    fun activate(engine: File) {
        val tmp = File(dir, "$ACTIVE.tmp")
        tmp.writeText(engine.name)
        if (!tmp.renameTo(File(dir, ACTIVE))) throw IOException("cannot activate ${engine.name}")
    }

    fun dexFiles(engine: File): List<File> = engine.listFiles { f -> f.name.endsWith(".dex") }.orEmpty().sortedBy { it.name }

    fun libDir(engine: File) = File(engine, "lib")

    fun resources(engine: File) = File(engine, RESOURCES)
}
