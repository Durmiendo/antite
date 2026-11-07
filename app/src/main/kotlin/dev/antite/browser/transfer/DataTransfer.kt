package dev.antite.browser.transfer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.antite.browser.data.Crypto
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Progress(val done: Long, val total: Long)

object DataTransfer {
    private const val FORMAT = 1
    private const val MANIFEST = "manifest.json"
    private const val VAULT = "databases/vault.db"
    private const val OUT_DIR = "transfer-out"
    private const val IN_DIR = "transfer-in"
    private const val OLD_DIR = "transfer-old"
    private val SKIPPED = setOf("cache", "code_cache", "lib", OUT_DIR, IN_DIR, OLD_DIR)
    private val SECRET_COLUMNS = listOf("logins" to "password", "addresses" to "data", "cards" to "number")

    fun export(context: Context, root: File, out: OutputStream, password: CharArray, progress: (Progress) -> Unit) {
        val work = File(root, OUT_DIR)
        work.deleteRecursively()
        work.mkdirs()
        try {
            val vault = File(root, VAULT)
            val plainVault = if (vault.exists()) File(work, "vault.db").also { copy ->
                vault.copyTo(copy)
                File(vault.path + "-wal").takeIf { it.exists() }?.copyTo(File(copy.path + "-wal"))
                rewriteSecrets(copy) { Crypto.decrypt(it) }
            } else null

            val files = collect(root).filter { (rel, _) -> !rel.startsWith("databases/vault.db") }
            val total = files.sumOf { it.second.length() } + (plainVault?.length() ?: 0)
            var done = 0L
            ZipOutputStream(BackupCipher.encrypt(out, password)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(JSONObject()
                    .put("format", FORMAT)
                    .put("package", context.packageName)
                    .put("versionCode", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
                    .put("created", System.currentTimeMillis())
                    .toString().toByteArray())
                zip.closeEntry()
                val entries = files + listOfNotNull(plainVault?.let { VAULT to it })
                for ((rel, file) in entries) {
                    if (file.isDirectory) {
                        zip.putNextEntry(ZipEntry("$rel/"))
                    } else {
                        zip.putNextEntry(ZipEntry(rel))
                        file.inputStream().use { input ->
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
            }
        } finally {
            work.deleteRecursively()
        }
    }

    fun import(context: Context, root: File, input: InputStream, total: Long, password: CharArray, progress: (Progress) -> Unit) {
        val stage = File(root, IN_DIR)
        stage.deleteRecursively()
        stage.mkdirs()
        try {
            val counted = CountingStream(input) { progress(Progress(it, total)) }
            val plain = BackupCipher.decrypt(counted, password)
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
                    val top = e.name.substringBefore('/')
                    if (top in SKIPPED) continue
                    if (e.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
                val buf = ByteArray(64 * 1024)
                while (plain.read(buf) >= 0) Unit
            }
            File(stage, VAULT).takeIf { it.exists() }?.let { v ->
                rewriteSecrets(v) { Crypto.encrypt(it) }
            }
            replace(root, stage)
            File(root, "cache").listFiles()?.forEach { it.deleteRecursively() }
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun replace(root: File, stage: File) {
        val old = File(root, OLD_DIR)
        old.deleteRecursively()
        old.mkdirs()
        val current = root.listFiles()?.filter { it.name !in SKIPPED }.orEmpty()
        try {
            for (f in current) if (!f.renameTo(File(old, f.name))) throw IOException("cannot move ${f.name}")
            stage.listFiles()?.forEach { f ->
                if (!f.renameTo(File(root, f.name))) throw IOException("cannot move ${f.name}")
            }
        } catch (e: IOException) {
            root.listFiles()?.filter { it.name !in SKIPPED }?.forEach { it.deleteRecursively() }
            old.listFiles()?.forEach { it.renameTo(File(root, it.name)) }
            throw e
        }
        old.deleteRecursively()
    }

    private fun collect(root: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        fun walk(dir: File, rel: String) {
            val children = dir.listFiles() ?: return
            if (children.isEmpty() && rel.isNotEmpty()) out += rel to dir
            for (c in children) {
                if (Files.isSymbolicLink(c.toPath())) continue
                val r = if (rel.isEmpty()) c.name else "$rel/${c.name}"
                if (rel.isEmpty() && c.name in SKIPPED) continue
                if (c.isDirectory) walk(c, r) else if (c.isFile) out += r to c
            }
        }
        walk(root, "")
        return out
    }

    private fun rewriteSecrets(dbFile: File, transform: (String) -> String?) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            for ((table, column) in SECRET_COLUMNS) {
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
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
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
