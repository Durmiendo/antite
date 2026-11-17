package dev.antite.browser.transfer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.antite.browser.data.Db
import dev.antite.browser.data.Secure
import dev.antite.browser.data.Vault
import dev.antite.browser.l10n.T
import dev.antite.keys.BackupCipher
import dev.antite.keys.Disk
import dev.antite.keys.KeyRing
import dev.antite.keys.input
import dev.antite.keys.output
import dev.antite.keys.readAll
import dev.antite.keys.replace
import dev.antite.keys.syncTree
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Progress(val done: Long, val total: Long)

object DataTransfer {
    private const val FORMAT = 1
    private const val MANIFEST = "manifest.json"
    private const val VAULT = "databases/vault.db"
    // The data keys travel in the open inside the encrypted backup; this device wraps them on import.
    private const val KEYRING = "keyring/"
    private const val SETTINGS = "shared_prefs/antite.xml"
    private val DEBUG_INSTALL = Regex("""\s*<boolean name="debugInstall" value="[^"]*" />""")
    // A database from a newer Antite cannot be opened by this one (no downgrade), so such a backup is refused.
    private val SCHEMAS = listOf("databases/antite.db" to Db.VERSION, VAULT to Vault.VERSION)

    // Finishes or clears an import a stop cut short, before anything opens the keys or the data. Skipped while a
    // transfer is running in another process: it finishes its own.
    fun recover(context: Context, disk: Disk = Secure.disk(context), root: File = context.dataDir) {
        disk.tryLock(File(root, DataSwap.LOCK))?.use { DataSwap(disk, root).recover { adopt -> keys(context, adopt) } } ?: return
    }

    private fun keys(context: Context, adopt: Boolean) {
        val ring = Secure.ring(context)
        if (adopt) ring.adoptImports() else ring.dropImports()
    }

    private fun locked(disk: Disk, root: File): java.io.Closeable =
        disk.tryLock(File(root, DataSwap.LOCK)) ?: throw IOException("another transfer is running")

    fun export(
        context: Context, root: File, out: OutputStream, password: CharArray, ring: KeyRing = Secure.ring(context),
        disk: Disk = Secure.disk(context), progress: (Progress) -> Unit,
    ) = locked(disk, root).use {
        val files = collect(disk, root).filter { (rel, _) -> !isEngine(rel) }
        val total = files.sumOf { (_, f) -> if (disk.isDirectory(f)) 0L else disk.length(f) }
        var done = 0L
        ZipOutputStream(BackupCipher.encrypt(out, password, Secure.Argon2)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(JSONObject()
                .put("format", FORMAT)
                .put("package", context.packageName)
                .put("versionCode", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
                .put("created", System.currentTimeMillis())
                .toString().toByteArray())
            zip.closeEntry()
            for ((rel, file) in files) {
                if (disk.isDirectory(file)) {
                    zip.putNextEntry(ZipEntry("$rel/"))
                } else {
                    zip.putNextEntry(ZipEntry(rel))
                    disk.input(file).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                            done += n
                            progress(Progress(done, total))
                        }
                    }
                }
                zip.closeEntry()
            }
            // Straight from the ring into the encrypted stream: never a file of their own.
            for (d in KeyRing.DOMAINS) {
                zip.putNextEntry(ZipEntry("$KEYRING$d"))
                ring.withKey(d) { zip.write(it) }
                zip.closeEntry()
            }
        }
    }

    fun import(
        context: Context, root: File, input: InputStream, total: Long, password: CharArray, ring: KeyRing = Secure.ring(context),
        disk: Disk = Secure.disk(context), progress: (Progress) -> Unit,
    ) = locked(disk, root).use {
        val swap = DataSwap(disk, root)
        swap.recover { adopt -> if (adopt) ring.adoptImports() else ring.dropImports() }
        val stage = swap.stage
        val keys = HashMap<String, ByteArray>()
        try {
            disk.mkdirs(stage)
            val counted = CountingStream(input) { progress(Progress(it, total)) }
            val plain = BackupCipher.decrypt(counted, password, Secure.Argon2)
            ZipInputStream(plain).use { zip ->
                val first = zip.nextEntry ?: throw IOException("empty backup")
                if (first.name != MANIFEST) throw IOException("no manifest")
                val manifest = JSONObject(String(zip.readBytes()))
                if (manifest.optInt("format") != FORMAT) throw IOException("unsupported backup format")
                if (manifest.optString("package") != context.packageName) throw IOException("backup of another app")
                val base = stage.canonicalFile
                while (true) {
                    val e = zip.nextEntry ?: break
                    val target = File(stage, e.name).canonicalFile
                    if (!target.path.startsWith(base.path + File.separator)) throw IOException("bad entry ${e.name}")
                    if (e.name.startsWith(KEYRING)) {
                        val d = e.name.removePrefix(KEYRING)
                        if (d in KeyRing.DOMAINS && !e.isDirectory) keys[d] = zip.readBytes()
                        continue
                    }
                    val top = e.name.substringBefore('/')
                    if (top in DataSwap.SKIPPED || isEngine(e.name)) continue
                    if (e.isDirectory) {
                        disk.mkdirs(target)
                    } else {
                        disk.mkdirs(target.parentFile)
                        disk.output(target, sync = true).use { zip.copyTo(it) }
                    }
                }
                val buf = ByteArray(64 * 1024)
                while (plain.read(buf) >= 0) Unit
            }
            for ((rel, version) in SCHEMAS) {
                val f = File(stage, rel).takeIf { disk.exists(it) } ?: continue
                val found = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
                if (found > version) throw IOException(T.transferNewerBackup)
            }
            // A backup from before the key ring holds its secrets in the open: they are sealed with this device's key.
            if (keys[KeyRing.SECRETS] == null) File(stage, VAULT).takeIf { disk.exists(it) }?.let { v ->
                ring.withKey(KeyRing.SECRETS) { k -> rewriteSecrets(disk, v) { Vault.seal(k, Secure.cipher(context), it) } }
            }
            File(stage, SETTINGS).takeIf { disk.exists(it) }?.let { f ->
                disk.replace(f, String(disk.readAll(f)).replace(DEBUG_INSTALL, "").toByteArray())
            }
            disk.syncTree(stage)
            for ((d, k) in keys) ring.stageImport(d, k)
            swap.commit()
        } catch (e: Throwable) {
            keys.values.forEach { it.fill(0) }
            swap.recover { adopt -> if (adopt) ring.adoptImports() else ring.dropImports() }
            throw e
        }
        // Past the commit the import stands: what stops it here, the next start carries through.
        try {
            swap.recover { adopt -> if (adopt) ring.adoptImports() else ring.dropImports() }
        } catch (e: IOException) {
            throw Unfinished(e)
        }
    }

    class Unfinished(cause: IOException) : IOException(cause.message, cause)

    private fun isEngine(rel: String) = DataSwap.DEVICE_ONLY.any { rel == it || rel.startsWith("$it/") }

    private fun collect(disk: Disk, root: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        fun walk(dir: File, rel: String) {
            val children = disk.children(dir)
            if (children.isEmpty() && rel.isNotEmpty()) out += rel to dir
            for (c in children) {
                if (disk.isLink(c)) continue
                val r = if (rel.isEmpty()) c.name else "$rel/${c.name}"
                if (rel.isEmpty() && c.name in DataSwap.SKIPPED) continue
                if (disk.isDirectory(c)) walk(c, r) else out += r to c
            }
        }
        walk(root, "")
        return out
    }

    private fun rewriteSecrets(disk: Disk, dbFile: File, transform: (String) -> String?) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            for ((table, column) in Vault.SECRET_COLUMNS) {
                val rows = db.rawQuery("SELECT guid, $column FROM $table", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
                }
                for ((guid, value) in rows) {
                    val v = transform(value)
                    if (v == null) {
                        db.delete(table, "guid=?", arrayOf(guid))
                    } else {
                        db.execSQL("UPDATE $table SET $column=? WHERE guid=?", arrayOf(v, guid))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.close()
        }
        disk.delete(File(dbFile.path + "-wal"))
        disk.delete(File(dbFile.path + "-shm"))
    }

    private class CountingStream(input: InputStream, private val report: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) report(++count) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
            if (it > 0) {
                count += it
                report(count)
            }
        }
    }
}
