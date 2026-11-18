package dev.antite.browser.e2etools

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.antite.browser.data.Vault
import dev.antite.keys.Cipher256
import dev.antite.keys.VaultFile
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

// Not a test: lets an e2e script see which cipher everything on the disk is sealed with, without the keys: each box
// and vault file names its cipher in the clear. Fails if anything is in another cipher than the one asked for, and
// reports what it counted.
//   am instrument -w -e class dev.antite.browser.e2etools.CipherTool -e expect 2 ...
@RunWith(AndroidJUnit4::class)
class CipherTool {
    private val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir

    private fun logBoxes(f: File): List<Int> {
        val b = f.readBytes()
        val out = ArrayList<Int>()
        var pos = 256
        while (pos + 4 <= b.size) {
            val len = ByteBuffer.wrap(b, pos, 4).int
            if (len <= 0 || pos + 4 + len > b.size) break
            out += b[pos + 4].toInt()
            pos += 4 + len
        }
        return out
    }

    private fun secrets(): List<Int> {
        val f = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("vault.db")
        if (!f.exists()) return emptyList()
        val out = ArrayList<Int>()
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for ((table, column) in Vault.SECRET_COLUMNS) {
                db.rawQuery("SELECT $column FROM $table", null).use { c ->
                    while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotEmpty() }?.let { out += Base64.decode(it, Base64.NO_WRAP)[0].toInt() }
                }
            }
        }
        return out
    }

    @Test
    fun everythingInOneCipher() {
        val expect = InstrumentationRegistry.getArguments().getString("expect")?.toInt()
        assumeTrue("run only from e2e scripts with -e expect", expect != null)
        val found = LinkedHashMap<String, Int>()
        File(files, "vault").listFiles().orEmpty().forEach { f ->
            found[f.path] = when {
                f.name == "index.box" -> f.readBytes()[0].toInt()
                // -1: a file that should be a vault file and is not one, so it shows up as wrong.
                f.name.endsWith(".enc") || f.name.endsWith(".thumb") ->
                    runCatching { VaultFile.head(f.readBytes().copyOf(VaultFile.HEADER)).cipher.id }.getOrDefault(-1)
                else -> return@forEach
            }
        }
        File(files, "history").listFiles().orEmpty().filter { it.name.endsWith(".log") }.forEach { f ->
            logBoxes(f).forEachIndexed { i, c -> found["${f.path}#$i"] = c }
        }
        File(files, "history/keys").listFiles().orEmpty().filter { it.name.endsWith(".key") }.forEach { f -> found[f.path] = f.readBytes()[0].toInt() }
        secrets().forEachIndexed { i, c -> found["vault.db#$i"] = c }
        val wrong = found.filterValues { it != expect }
        val counts = found.values.groupingBy { c -> Cipher256.entries.firstOrNull { it.id == c }?.name ?: "id $c" }.eachCount()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("ciphers", counts.toString()) })
        assertEquals("sealed in another cipher than $expect (counts $counts)", emptyMap<String, Int>(), wrong)
    }
}
