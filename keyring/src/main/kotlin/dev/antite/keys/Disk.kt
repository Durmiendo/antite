package dev.antite.keys

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

sealed class DiskException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class NotFound(path: String) : DiskException("no file $path")
    class OutsideRoot(path: String) : DiskException("$path is outside the app's folder or goes through a link")
    class Failed(what: String, cause: Throwable? = null) : DiskException(what, cause)
}

// The only way to the disk for keys and encrypted stores. Nothing written is on the disk until sync(); a rename or a
// delete is not until syncFolder() of its folder. Tests put a disk here that loses exactly what these promise to lose.
interface Disk {
    enum class Open { READ, WRITE }

    interface Handle : Closeable {
        val length: Long
        // Up to len bytes at pos; -1 at the end.
        fun read(pos: Long, b: ByteArray, off: Int = 0, len: Int = b.size): Int
        fun write(pos: Long, b: ByteArray, off: Int = 0, len: Int = b.size)
        fun truncate(size: Long)
        fun sync()
    }

    fun exists(f: File): Boolean
    fun isDirectory(f: File): Boolean
    fun length(f: File): Long
    fun modified(f: File): Long
    // The files in a folder; none if there is no folder.
    fun list(dir: File): List<File>
    // Files and folders in a folder, links left out; none if there is no folder.
    fun children(dir: File): List<File>
    // Makes the missing folders, each one on the disk before the next.
    fun mkdirs(dir: File)
    // WRITE makes a missing file.
    fun open(f: File, mode: Open): Handle
    // Puts a file in place of to; a folder only where nothing is.
    fun rename(from: File, to: File)
    // A file or an empty folder.
    fun delete(f: File): Boolean
    fun syncFolder(dir: File)
    // Held until closed, also against other processes; null while someone else holds it.
    fun tryLock(f: File): Closeable?
}

fun Disk.Handle.readFully(pos: Long, b: ByteArray) {
    var done = 0
    while (done < b.size) {
        val n = read(pos + done, b, done, b.size - done)
        if (n < 0) throw DiskException.Failed("file ends early")
        done += n
    }
}

fun Disk.readAll(f: File): ByteArray = open(f, Disk.Open.READ).use { h ->
    if (h.length > Int.MAX_VALUE) throw DiskException.Failed("${f.name} is too large to read whole")
    ByteArray(h.length.toInt()).also { h.readFully(0, it) }
}

// A file written aside, synced and renamed over: a stop anywhere leaves either the old one or the new one.
fun Disk.replace(f: File, bytes: ByteArray) {
    val tmp = File(f.path + ".tmp")
    open(tmp, Disk.Open.WRITE).use { h ->
        h.truncate(0)
        h.write(0, bytes)
        h.sync()
    }
    renameDurably(tmp, f)
}

fun Disk.renameDurably(from: File, to: File) {
    rename(from, to)
    syncFolder(to.absoluteFile.parentFile)
    if (from.absoluteFile.parentFile != to.absoluteFile.parentFile) syncFolder(from.absoluteFile.parentFile)
}

fun Disk.deleteDurably(f: File) {
    if (delete(f)) syncFolder(f.absoluteFile.parentFile)
}

// A file or a folder with all in it, gone once this returns.
fun Disk.deleteTree(f: File) {
    if (!exists(f)) return
    if (isDirectory(f)) children(f).forEach { deleteTree(it) }
    deleteDurably(f)
}

// Every folder under dir on the disk, so the files written into them are reachable after a power cut.
fun Disk.syncTree(dir: File) {
    children(dir).filter { isDirectory(it) }.forEach { syncTree(it) }
    syncFolder(dir)
}

// Written from the start of a new or emptied file. sync = true makes close() put it on the disk first.
fun Disk.output(f: File, sync: Boolean): OutputStream {
    val h = open(f, Disk.Open.WRITE).also { it.truncate(0) }
    return object : OutputStream() {
        private var pos = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            h.write(pos, b, off, len)
            pos += len
        }

        override fun close() {
            if (closed) return
            closed = true
            h.use { if (sync) it.sync() }
        }
    }
}

fun Disk.input(f: File): InputStream {
    val h = open(f, Disk.Open.READ)
    return object : InputStream() {
        private var pos = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return h.read(pos, b, off, len).also { if (it > 0) pos += it }
        }

        override fun close() = h.close()
    }
}

// The real disk, only under root: a path that leaves it, by ".." or by a link anywhere below root, is refused.
class RealDisk(root: File) : Disk {
    private val root = root.absoluteFile.normalize()
    private val realRoot = root.canonicalFile

    private fun checked(f: File): File {
        val abs = f.absoluteFile.normalize()
        val rel = abs.toRelativeString(root)
        if (abs != root && (rel.startsWith("..") || File(rel).isAbsolute)) throw DiskException.OutsideRoot(f.path)
        val real = try {
            abs.canonicalFile
        } catch (e: IOException) {
            throw DiskException.Failed("cannot resolve ${f.path}", e)
        }
        if (real != (if (rel.isEmpty()) realRoot else File(realRoot, rel))) throw DiskException.OutsideRoot(f.path)
        return abs
    }

    private inline fun <T> io(what: String, block: () -> T): T = try {
        block()
    } catch (e: DiskException) {
        throw e
    } catch (e: IOException) {
        throw DiskException.Failed(what, e)
    }

    override fun exists(f: File) = checked(f).exists()

    override fun isDirectory(f: File) = checked(f).isDirectory

    override fun length(f: File): Long = checked(f).let { if (it.isFile) it.length() else throw DiskException.NotFound(f.path) }

    override fun modified(f: File): Long = checked(f).let { if (it.exists()) it.lastModified() else throw DiskException.NotFound(f.path) }

    override fun list(dir: File): List<File> = children(dir).filter { it.isFile }

    override fun children(dir: File): List<File> =
        checked(dir).listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) }.sortedBy { it.name }

    override fun mkdirs(dir: File) {
        val d = checked(dir)
        if (d.isDirectory) return
        d.parentFile?.let { if (it.path.startsWith(root.path)) mkdirs(it) }
        if (!d.mkdir() && !d.isDirectory) throw DiskException.Failed("cannot make ${dir.path}")
        syncFolder(d.parentFile)
    }

    override fun open(f: File, mode: Disk.Open): Disk.Handle {
        val file = checked(f)
        if (mode == Disk.Open.READ && !file.isFile) throw DiskException.NotFound(f.path)
        val raf = io("cannot open ${f.name}") { RandomAccessFile(file, if (mode == Disk.Open.READ) "r" else "rw") }
        return object : Disk.Handle {
            override val length get() = io("cannot size ${f.name}") { raf.length() }

            override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int = io("cannot read ${f.name}") {
                raf.seek(pos)
                raf.read(b, off, len)
            }

            override fun write(pos: Long, b: ByteArray, off: Int, len: Int) = io("cannot write ${f.name}") {
                raf.seek(pos)
                raf.write(b, off, len)
            }

            override fun truncate(size: Long) = io("cannot cut ${f.name}") { raf.setLength(size) }

            override fun sync() = io("cannot sync ${f.name}") { raf.fd.sync() }

            override fun close() = io("cannot close ${f.name}") { raf.close() }
        }
    }

    override fun rename(from: File, to: File) {
        val a = checked(from)
        val b = checked(to)
        if (!a.exists()) throw DiskException.NotFound(from.path)
        if (a.isDirectory && b.exists()) throw DiskException.Failed("${to.name} is in the way of folder ${from.name}")
        if (!a.renameTo(b)) throw DiskException.Failed("cannot rename ${from.name} to ${to.name}")
    }

    override fun delete(f: File): Boolean {
        val file = checked(f)
        if (!file.exists()) return false
        if (!file.delete()) throw DiskException.Failed("cannot delete ${f.name}")
        return true
    }

    override fun syncFolder(dir: File) = io("cannot sync folder ${dir.name}") {
        FileChannel.open(checked(dir).toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun tryLock(f: File): Closeable? = io("cannot lock ${f.name}") {
        val ch = FileChannel.open(checked(f).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try {
            ch.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            ch.close()
            null
        } else Closeable {
            lock.release()
            ch.close()
        }
    }
}
