package dev.antite.keys

import java.io.File

// A disk in memory that keeps exactly what Disk promises and nothing more. Every change is a step; the process can be
// made to die before any step, and the disk then comes back as after that death:
//   KILL  - the process is killed, the system keeps everything it was given;
//   POWER - the power is cut: what was not synced is gone, renames and deletes not synced in their folder are undone;
//   TORN  - as POWER, but the first half of the unsynced writes of each file got there, the last of them only half.
class CrashDisk : Disk {
    class Death : Error("the process died here")

    enum class Cut { KILL, POWER, TORN }

    private sealed interface Change {
        class Write(val pos: Long, val bytes: ByteArray) : Change
        class Cut(val size: Long) : Change
    }

    private sealed class Node {
        var modified = 0L
    }

    private class FileNode : Node() {
        var base = ByteArray(0)
        var data = ByteArray(0)
        val pending = ArrayList<Change>()
    }

    // A folder's entries as the process sees them, and as they are on the disk.
    private class Dir : Node() {
        val live = HashMap<String, Node>()
        val durable = HashMap<String, Node>()
    }

    private fun applied(data: ByteArray, c: Change): ByteArray = when (c) {
        is Change.Cut -> data.copyOf(c.size.toInt())
        is Change.Write -> {
            val end = (c.pos + c.bytes.size).toInt()
            val out = if (end > data.size) data.copyOf(end) else data.copyOf()
            System.arraycopy(c.bytes, 0, out, c.pos.toInt(), c.bytes.size)
            out
        }
    }

    private val root = Dir()

    // A rename is one change of the disk, as on a journaled file system: syncing either folder keeps it whole, in
    // both, so a cut never shows it in both places or in neither.
    private class Move(val src: Dir, val srcName: String, val dst: Dir, val dstName: String, val node: Node)

    private val moves = ArrayList<Move>()

    // The time files get as their modified time.
    var clock = 1_000_000L
    // Steps taken since the last settle() or restart().
    var steps = 0
        private set
    var dieAt = -1
    var dead = false
        private set

    private fun parts(f: File) = f.absoluteFile.normalize().path.split('/').filter { it.isNotEmpty() }

    private fun node(f: File): Node? {
        var n: Node = root
        for (p in parts(f)) n = (n as? Dir)?.live?.get(p) ?: return null
        return n
    }

    private fun parentDir(f: File): Dir {
        val ps = parts(f)
        var n: Node = root
        for (p in ps.dropLast(1)) n = (n as? Dir)?.live?.get(p) ?: throw DiskException.Failed("no folder for ${f.name}")
        return n as? Dir ?: throw DiskException.Failed("no folder for ${f.name}")
    }

    private fun name(f: File) = parts(f).last()

    // A change to the disk, or to anything else that outlives the process (the Keystore): the process may die here.
    fun step() {
        alive()
        if (steps == dieAt) {
            dead = true
            throw Death()
        }
        steps++
    }

    private fun alive() {
        if (dead) throw Death()
    }

    // Everything as it is now is on the disk; the step count starts again.
    fun settle() {
        fun walk(d: Dir) {
            d.durable.clear()
            d.durable.putAll(d.live)
            for (c in d.live.values) when (c) {
                is Dir -> walk(c)
                is FileNode -> {
                    c.base = c.data
                    c.pending.clear()
                }
            }
        }
        walk(root)
        moves.clear()
        steps = 0
        dieAt = -1
        dead = false
    }

    fun restart(cut: Cut) {
        if (cut != Cut.KILL) {
            val seen = HashSet<Node>()
            fun walk(d: Dir) {
                if (!seen.add(d)) return
                d.live.clear()
                d.live.putAll(d.durable)
                for (c in d.live.values) when (c) {
                    is Dir -> walk(c)
                    is FileNode -> if (seen.add(c)) {
                        var data = c.base
                        if (cut == Cut.TORN && c.pending.isNotEmpty()) {
                            val n = (c.pending.size + 1) / 2
                            for ((j, ch) in c.pending.take(n).withIndex()) {
                                val half = if (j == n - 1 && ch is Change.Write) Change.Write(ch.pos, ch.bytes.copyOf(ch.bytes.size / 2)) else ch
                                data = applied(data, half)
                            }
                        }
                        c.data = data
                    }
                }
            }
            walk(root)
        }
        settle()
    }

    fun files(): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        fun walk(d: Dir, path: String) {
            for ((n, c) in d.live) when (c) {
                is Dir -> walk(c, "$path/$n")
                is FileNode -> out["$path/$n"] = c.data.copyOf()
            }
        }
        walk(root, "")
        return out
    }

    // For setting up a test: a file there at once, as if long on the disk.
    fun write(f: File, bytes: ByteArray) {
        val ps = parts(f)
        var d = root
        for (p in ps.dropLast(1)) d = d.live.getOrPut(p) { Dir().also { n -> d.durable[p] = n } } as Dir
        val node = FileNode().also { it.data = bytes.copyOf(); it.base = it.data; it.modified = clock }
        d.live[ps.last()] = node
        d.durable[ps.last()] = node
    }

    override fun exists(f: File): Boolean {
        alive()
        return node(f) != null
    }

    override fun isDirectory(f: File): Boolean {
        alive()
        return node(f) is Dir
    }

    private fun fileNode(f: File) = node(f) as? FileNode ?: throw DiskException.NotFound(f.path)

    override fun length(f: File): Long {
        alive()
        return fileNode(f).data.size.toLong()
    }

    override fun modified(f: File): Long {
        alive()
        return (node(f) ?: throw DiskException.NotFound(f.path)).modified
    }

    override fun list(dir: File): List<File> = children(dir).filter { node(it) is FileNode }

    override fun children(dir: File): List<File> {
        alive()
        val d = node(dir) as? Dir ?: return emptyList()
        return d.live.keys.sorted().map { File(dir.absoluteFile.normalize(), it) }
    }

    override fun mkdirs(dir: File) {
        alive()
        var d = root
        for (p in parts(dir)) {
            val next = d.live[p]
            d = when (next) {
                is Dir -> next
                is FileNode -> throw DiskException.Failed("a file is in the way of ${dir.path}")
                null -> {
                    step()
                    Dir().also { n ->
                        n.modified = clock
                        d.live[p] = n
                        d.durable[p] = n
                    }
                }
            }
        }
    }

    override fun open(f: File, mode: Disk.Open): Disk.Handle {
        alive()
        val parent = parentDir(f)
        val node = when (val n = parent.live[name(f)]) {
            is FileNode -> n
            is Dir -> throw DiskException.Failed("${f.name} is a folder")
            null -> if (mode == Disk.Open.READ) throw DiskException.NotFound(f.path) else {
                step()
                FileNode().also { it.modified = clock; parent.live[name(f)] = it }
            }
        }
        return object : Disk.Handle {
            private var open = true

            private fun usable() {
                alive()
                if (!open) throw DiskException.Failed("${f.name} is closed")
            }

            private fun change(c: Change) {
                if (mode == Disk.Open.READ) throw DiskException.Failed("${f.name} is open for reading")
                step()
                node.data = applied(node.data, c)
                node.pending += c
                node.modified = clock
            }

            override val length: Long
                get() {
                    usable()
                    return node.data.size.toLong()
                }

            override fun read(pos: Long, b: ByteArray, off: Int, len: Int): Int {
                usable()
                if (pos >= node.data.size) return -1
                val n = minOf(len.toLong(), node.data.size - pos).toInt()
                System.arraycopy(node.data, pos.toInt(), b, off, n)
                return n
            }

            override fun write(pos: Long, b: ByteArray, off: Int, len: Int) {
                usable()
                if (len > 0) change(Change.Write(pos, b.copyOfRange(off, off + len)))
            }

            override fun truncate(size: Long) {
                usable()
                if (size != node.data.size.toLong()) change(Change.Cut(size))
            }

            override fun sync() {
                usable()
                step()
                node.base = node.data
                node.pending.clear()
            }

            override fun close() {
                open = false
            }
        }
    }

    override fun rename(from: File, to: File) {
        alive()
        val src = parentDir(from)
        val n = src.live[name(from)] ?: throw DiskException.NotFound(from.path)
        val dst = parentDir(to)
        val there = dst.live[name(to)]
        if (n is Dir && there != null) throw DiskException.Failed("${to.name} is in the way of folder ${from.name}")
        if (there is Dir) throw DiskException.Failed("${to.name} is a folder")
        step()
        src.live.remove(name(from))
        dst.live[name(to)] = n
        moves += Move(src, name(from), dst, name(to), n)
    }

    override fun delete(f: File): Boolean {
        alive()
        val parent = runCatching { parentDir(f) }.getOrNull() ?: return false
        val n = parent.live[name(f)] ?: return false
        if (n is Dir && n.live.isNotEmpty()) throw DiskException.Failed("folder ${f.name} is not empty")
        step()
        parent.live.remove(name(f))
        return true
    }

    override fun syncFolder(dir: File) {
        alive()
        val d = node(dir) as? Dir ?: throw DiskException.NotFound(dir.path)
        step()
        val done = moves.filter { it.src === d || it.dst === d }
        for (m in done) {
            if (m.src.durable[m.srcName] === m.node) m.src.durable.remove(m.srcName)
            m.dst.durable[m.dstName] = m.node
        }
        moves.removeAll(done.toSet())
        d.durable.clear()
        d.durable.putAll(d.live)
    }

    override fun tryLock(f: File): java.io.Closeable? {
        alive()
        return java.io.Closeable { }
    }
}

// Runs an action once to count its steps, then from the same start again and again, dying before each step in turn,
// under every kind of cut. check gets the disk after the restart and whether the action got to its end.
object Crashes {
    fun <S> everywhere(
        setup: (CrashDisk) -> S,
        action: (CrashDisk, S) -> Unit,
        check: (disk: CrashDisk, state: S, finished: Boolean, where: String) -> Unit,
    ) {
        val probe = CrashDisk()
        val s0 = setup(probe)
        probe.settle()
        action(probe, s0)
        val total = probe.steps
        for (cut in CrashDisk.Cut.entries) for (at in 0..total) {
            val d = CrashDisk()
            val s = setup(d)
            d.settle()
            d.dieAt = at
            try {
                action(d, s)
            } catch (e: CrashDisk.Death) {
                // The process is gone; whatever the action caught on the way does not matter.
            }
            val finished = !d.dead
            d.restart(cut)
            check(d, s, finished, "$cut before step $at of $total")
        }
    }
}

// A stand-in for the Keystore that outlives the process like the real one; making and destroying a key are steps the
// process may die before.
class CrashDevice(private val disk: CrashDisk) : KeyRing.DeviceKeys {
    val keys = HashMap<String, ByteArray>()

    override fun wrap(alias: String, plain: ByteArray): ByteArray {
        val k = keys[alias] ?: Ciphers.randomBytes(64).also {
            disk.step()
            keys[alias] = it
        }
        return Ciphers.box(Cipher256.AES, k, plain)
    }

    override fun unwrap(alias: String, blob: ByteArray) =
        Ciphers.unbox(keys[alias] ?: throw java.io.IOException("no key $alias"), blob)

    override fun destroy(alias: String) {
        if (alias !in keys) return
        disk.step()
        keys.remove(alias)
    }
}
