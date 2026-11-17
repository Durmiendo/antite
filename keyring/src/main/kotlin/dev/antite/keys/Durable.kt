package dev.antite.keys

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// A file replaced so that a power cut leaves either the old one or the new one, never an empty one: written aside and
// synced, renamed over, and the folder synced so the rename itself is on the disk.
object Durable {
    fun replace(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        rename(tmp, f)
    }

    fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("cannot replace ${to.name}")
        syncFolder(to.absoluteFile.parentFile)
    }

    private fun syncFolder(dir: File?) {
        dir ?: return
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
