package dev.antite.browser.transfer

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.antite.browser.data.Crypto
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class DataTransferTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var base: File
    private lateinit var src: File
    private lateinit var dst: File
    private val cookies = Random(7).nextBytes(3 * BackupCipher.CHUNK + 123)

    @Before
    fun setUp() {
        BackupCipher.iterations = 1000
        base = File(context.cacheDir, "transfer-test").apply { deleteRecursively(); mkdirs() }
        src = File(base, "src")
        dst = File(base, "dst")
        write(src, "files/tabs.json", "[1,2,3]".toByteArray())
        write(src, "files/mozilla/x.default/cookies.sqlite", cookies)
        write(src, "shared_prefs/antite.xml", "<map/>".toByteArray())
        File(src, "files/empty").mkdirs()
        write(src, "cache/junk", "junk".toByteArray())
        vault(File(src, "databases/vault.db"))
        write(dst, "files/old.txt", "old".toByteArray())
        write(dst, "cache/stale", "stale".toByteArray())
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun write(root: File, rel: String, bytes: ByteArray) {
        File(root, rel).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    private fun vault(file: File) {
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE logins (guid TEXT PRIMARY KEY, password TEXT NOT NULL)")
            db.execSQL("CREATE TABLE addresses (guid TEXT PRIMARY KEY, data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE cards (guid TEXT PRIMARY KEY, number TEXT NOT NULL)")
            db.execSQL("INSERT INTO logins VALUES ('a', ?), ('broken', 'not-encrypted')", arrayOf(Crypto.encrypt("pw-alice")))
            db.execSQL("INSERT INTO addresses VALUES ('h', ?)", arrayOf(Crypto.encrypt("{\"name\":\"Ivan\"}")))
            db.execSQL("INSERT INTO cards VALUES ('c', ?)", arrayOf(Crypto.encrypt("4111111111111111")))
        }
    }

    private fun column(file: File, table: String, column: String): Map<String, String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT guid, $column FROM $table", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }
        }

    private fun export(password: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataTransfer.export(context, src, out, password.toCharArray()) {}
        return out.toByteArray()
    }

    private fun import(backup: ByteArray, password: String) =
        DataTransfer.import(context, dst, ByteArrayInputStream(backup), backup.size.toLong(), password.toCharArray()) {}

    @Test
    fun roundTripRestoresEverything() {
        import(export("secret"), "secret")

        assertEquals("[1,2,3]", File(dst, "files/tabs.json").readText())
        assertArrayEquals(cookies, File(dst, "files/mozilla/x.default/cookies.sqlite").readBytes())
        assertEquals("<map/>", File(dst, "shared_prefs/antite.xml").readText())
        assertTrue(File(dst, "files/empty").isDirectory)
        assertFalse("cache is not exported", File(dst, "cache/junk").exists())
        assertFalse("stale cache is cleared", File(dst, "cache/stale").exists())
        assertFalse("old data is replaced", File(dst, "files/old.txt").exists())
        assertFalse(File(dst, "transfer-in").exists())

        val v = File(dst, "databases/vault.db")
        val logins = column(v, "logins", "password")
        assertEquals(setOf("a"), logins.keys)
        assertNotEquals("stored encrypted, not as plain text", "pw-alice", logins["a"])
        assertEquals("pw-alice", Crypto.decrypt(logins["a"]))
        assertEquals("{\"name\":\"Ivan\"}", Crypto.decrypt(column(v, "addresses", "data")["h"]))
        assertEquals("4111111111111111", Crypto.decrypt(column(v, "cards", "number")["c"]))
    }

    @Test
    fun engineIsNeitherExportedNorReplaced() {
        write(src, "files/engines/old-engine/ready", byteArrayOf())
        write(dst, "files/engines/current/ready", byteArrayOf())
        write(dst, "files/engines/active", "current".toByteArray())
        import(export("secret"), "secret")
        assertFalse(File(dst, "files/engines/old-engine").exists())
        assertTrue(File(dst, "files/engines/current/ready").exists())
        assertEquals("current", File(dst, "files/engines/active").readText())
        assertEquals("[1,2,3]", File(dst, "files/tabs.json").readText())
    }

    @Test
    fun exportLeavesSourceUntouched() {
        val before = column(File(src, "databases/vault.db"), "logins", "password")
        export("secret")
        assertEquals(before, column(File(src, "databases/vault.db"), "logins", "password"))
        assertFalse(File(src, "transfer-out").exists())
    }

    @Test
    fun wrongPasswordChangesNothing() {
        val backup = export("secret")
        assertThrows(WrongPasswordException::class.java) { import(backup, "nope") }
        assertEquals("old", File(dst, "files/old.txt").readText())
        assertFalse(File(dst, "files/tabs.json").exists())
        assertFalse(File(dst, "transfer-in").exists())
    }

    @Test
    fun damagedFileChangesNothing() {
        val backup = export("secret")
        backup[backup.size - 100] = (backup[backup.size - 100].toInt() xor 1).toByte()
        assertThrows(WrongPasswordException::class.java) { import(backup, "secret") }
        assertEquals("old", File(dst, "files/old.txt").readText())
        assertFalse(File(dst, "files/tabs.json").exists())
    }
}
