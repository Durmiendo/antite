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

    private inner class Inode {
        var base = ByteArray(0)
        var data = ByteArray(0)
        val pending = ArrayList<Change>()
        var modified = clock

        fun apply(c: Change) {
            data = applied(data, c)
            pending += c
            modified = clock
        }
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

    private val live = HashMap<String, Inode>()
    private val durable = HashMap<String, Inode>()
    private val dirs = HashSet<String>()

    // The time files get as their modified time.
    var clock = 1_000_000L
    // Steps taken since the last settle() or restart().
    var steps = 0
        private set
    var dieAt = -1
    var dead = false
        private set

    private fun key(f: File) = f.absoluteFile.normalize().path
    private fun parent(path: String) = File(path).parent

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
        for (i in live.values) {
            i.base = i.data
            i.pending.clear()
        }
        durable.clear()
        durable.putAll(live)
        steps = 0
        dieAt = -1
        dead = false
    }

    fun restart(cut: Cut) {
        if (cut != Cut.KILL) {
            live.clear()
            live.putAll(durable)
            for (i in live.values) {
                var data = i.base
                if (cut == Cut.TORN && i.pending.isNotEmpty()) {
                    val n = (i.pending.size + 1) / 2
                    for ((j, c) in i.pending.take(n).withIndex()) {
                        val half = if (j == n - 1 && c is Change.Write) Change.Write(c.pos, c.bytes.copyOf(c.bytes.size / 2)) else c
                        data = applied(data, half)
                    }
                }
                i.data = data
            }
        }
        settle()
    }

    fun files(): Map<String, ByteArray> = live.mapValues { it.value.data.copyOf() }

    fun write(f: File, bytes: ByteArray) {
        mkdirs(f.absoluteFile.parentFile)
        live[key(f)] = Inode().also { it.data = bytes.copyOf() }
    }

    override fun exists(f: File): Boolean {
        alive()
        return key(f) in live || key(f) in dirs
    }

    private fun inode(f: File) = live[key(f)] ?: throw DiskException.NotFound(f.path)

    override fun length(f: File): Long {
        alive()
        return inode(f).data.size.toLong()
    }

    override fun modified(f: File): Long {
        alive()
        return inode(f).modified
    }

    override fun list(dir: File): List<File> {
        alive()
        val d = key(dir)
        return live.keys.filter { parent(it) == d }.sorted().map(::File)
    }

    override fun mkdirs(dir: File) {
        alive()
        var d: File? = dir.absoluteFile.normalize()
        while (d != null) {
            dirs += d.path
            d = d.parentFile
        }
    }

    override fun open(f: File, mode: Disk.Open): Disk.Handle {
        alive()
        val k = key(f)
        if (parent(k) !in dirs) throw DiskException.Failed("no folder for ${f.name}")
        val node = live[k] ?: if (mode == Disk.Open.READ) throw DiskException.NotFound(f.path) else {
            step()
            Inode().also { live[k] = it }
        }
        return object : Disk.Handle {
            private var open = true

            private fun usable() {
                alive()
                if (!open) throw DiskException.Failed("${f.name} is closed")
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
                if (mode == Disk.Open.READ) throw DiskException.Failed("${f.name} is open for reading")
                if (len == 0) return
                step()
                node.apply(Change.Write(pos, b.copyOfRange(off, off + len)))
            }

            override fun truncate(size: Long) {
                usable()
                if (mode == Disk.Open.READ) throw DiskException.Failed("${f.name} is open for reading")
                if (size == node.data.size.toLong()) return
                step()
                node.apply(Change.Cut(size))
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
        val node = inode(from)
        if (parent(key(to)) !in dirs) throw DiskException.Failed("no folder for ${to.name}")
        step()
        live.remove(key(from))
        live[key(to)] = node
    }

    override fun delete(f: File): Boolean {
        alive()
        if (key(f) !in live) return false
        step()
        live.remove(key(f))
        return true
    }

    override fun syncFolder(dir: File) {
        alive()
        step()
        val d = key(dir)
        for (k in (live.keys + durable.keys).filter { parent(it) == d }) {
            val node = live[k]
            if (node == null) durable.remove(k) else durable[k] = node
        }
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
