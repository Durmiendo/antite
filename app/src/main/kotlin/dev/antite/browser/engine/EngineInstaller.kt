package dev.antite.browser.engine

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

fun interface Fetcher {
    fun open(url: String): InputStream
}

class EngineInstaller(private val enginesDir: File, private val fetcher: Fetcher) {
    fun install(entry: EngineEntry, abi: String, progress: (done: Long, total: Long) -> Unit): File {
        val upstream = entry.upstream[abi] ?: throw IOException("no engine for $abi")
        val target = File(enginesDir, entry.dirName)
        if (File(target, EngineStore.READY).exists()) return target
        val tmp = File(enginesDir, entry.dirName + ".tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
        try {
            val total = entry.adapter.size + upstream.size
            val adapter = download(entry.adapter, File(tmp, "adapter.zip")) { progress(it, total) }
            val aar = download(upstream, File(tmp, "engine.aar")) { progress(entry.adapter.size + it, total) }
            assemble(entry, adapter, aar, abi, tmp)
            adapter.delete()
            aar.delete()
            if (!File(tmp, EngineStore.READY).createNewFile()) throw IOException("cannot mark engine ready")
            tmp.walkBottomUp().filter { it.isFile }.forEach { it.setReadOnly() }
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw IOException("cannot move engine into place")
            return target
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        }
    }

    private fun download(a: Artifact, to: File, progress: (Long) -> Unit): File {
        var last: Exception? = null
        for (url in a.urls) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                fetcher.open(url).use { input ->
                    to.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            count += n
                            if (count > a.size) throw IOException("$url is larger than expected")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            progress(count)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (count != a.size || hash != a.sha256) throw IOException("$url does not match the index")
                return to
            } catch (e: Exception) {
                last = e
                to.delete()
            }
        }
        throw last ?: IOException("no download address")
    }

    private fun assemble(entry: EngineEntry, adapter: File, aar: File, abi: String, dir: File) {
        val assets = mutableListOf<Pair<String, ByteArray>>()
        ZipFile(adapter).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val name = safeName(e.name)
                when {
                    name == "engine.json" || (name.endsWith(".dex") && '/' !in name) ->
                        File(dir, name).outputStream().use { z.getInputStream(e).copyTo(it) }
                    name.startsWith("assets/") -> assets += name to z.getInputStream(e).readBytes()
                }
            }
        }
        val manifest = JSONObject(File(dir, "engine.json").readText())
        if (manifest.getString("type") != entry.type || manifest.getInt("api") != entry.api ||
            manifest.getString("version") != entry.version
        ) {
            throw IOException("adapter does not match the index entry")
        }
        val lib = File(dir, "lib").apply { mkdirs() }
        var libs = 0
        val omni = File(dir, "omni.tmp")
        ZipFile(aar).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val name = safeName(e.name)
                if (name.startsWith("jni/$abi/") && name.endsWith(".so")) {
                    File(lib, name.substringAfterLast('/')).outputStream().use { z.getInputStream(e).copyTo(it) }
                    libs++
                } else if (name == "assets/omni.ja") {
                    omni.outputStream().use { z.getInputStream(e).copyTo(it) }
                }
            }
        }
        if (libs == 0 || !File(lib, "libxul.so").exists()) throw IOException("engine has no libraries for $abi")
        if (!omni.exists()) throw IOException("engine has no omni.ja")
        ZipOutputStream(File(dir, EngineStore.RESOURCES).outputStream()).use { zip ->
            stored(zip, "assets/omni.ja", omni)
            for ((name, bytes) in assets) stored(zip, name, bytes)
        }
        omni.delete()
    }

    private fun safeName(name: String): String {
        if (name.startsWith("/") || name.split('/').any { it == ".." }) throw IOException("bad entry name $name")
        return name
    }

    private fun stored(zip: ZipOutputStream, name: String, file: File) {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = file.length()
            compressedSize = file.length()
            this.crc = crc.value
        })
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun stored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().also { it.update(bytes) }.value
        })
        zip.write(bytes)
        zip.closeEntry()
    }
}
