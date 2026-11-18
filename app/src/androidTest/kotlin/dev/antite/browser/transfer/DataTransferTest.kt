package dev.antite.browser.transfer

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.antite.browser.data.SecretBox
import dev.antite.browser.data.Secure
import dev.antite.browser.data.Vault
import dev.antite.keys.BackupCipher
import dev.antite.keys.Cipher256
import dev.antite.keys.Ciphers
import dev.antite.keys.CoreKey
import dev.antite.keys.KeyRing
import dev.antite.keys.VaultFile
import dev.antite.keys.WrongPasswordException
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class DataTransferTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var base: File
    private lateinit var src: File
    private lateinit var dst: File
    private val cookies = Random(7).nextBytes(3 * BackupCipher.CHUNK + 123)
    // Each side has its own key ring where the app keeps it, with Keystore aliases of the tests' own.
    private lateinit var srcRing: KeyRing
    private lateinit var dstRing: KeyRing

    @Before
    fun setUp() {
        BackupCipher.params = KeyRing.KdfParams(1, 64, 1)
        base = File(context.cacheDir, "transfer-test").apply { deleteRecursively(); mkdirs() }
        src = File(base, "src")
        dst = File(base, "dst")
        write(src, "files/tabs.json", "[1,2,3]".toByteArray())
        write(src, "files/mozilla/x.default/cookies.sqlite", cookies)
        write(src, "shared_prefs/antite.xml", "<map/>".toByteArray())
        File(src, "files/empty").mkdirs()
        write(src, "cache/junk", "junk".toByteArray())
        srcRing = KeyRing(Secure.disk(context), File(src, "files/keys"), Secure.Keystore, Secure.Argon2, "antite-test-src").apply { create() }
        dstRing = KeyRing(Secure.disk(context), File(dst, "files/keys"), Secure.Keystore, Secure.Argon2, "antite-test-dst").apply { create() }
        vault(File(src, "databases/vault.db"))
        write(dst, "files/old.txt", "old".toByteArray())
        write(dst, "cache/stale", "stale".toByteArray())
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
        for (prefix in listOf("antite-test-src", "antite-test-dst")) for (d in KeyRing.DOMAINS) for (gen in 1..5) {
            runCatching { Secure.Keystore.destroy("$prefix-$d-$gen") }
        }
    }

    private fun seal(plain: String) = SecretBox.seal(srcRing.key(KeyRing.SECRETS), Cipher256.AES, plain)

    private fun write(root: File, rel: String, bytes: ByteArray) {
        File(root, rel).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    private fun vault(file: File) {
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE logins (guid TEXT PRIMARY KEY, password TEXT NOT NULL)")
            db.execSQL("CREATE TABLE addresses (guid TEXT PRIMARY KEY, data TEXT NOT NULL)")
            db.execSQL("CREATE TABLE cards (guid TEXT PRIMARY KEY, number TEXT NOT NULL)")
            db.execSQL("INSERT INTO logins VALUES ('a', ?)", arrayOf(seal("pw-alice")))
            db.execSQL("INSERT INTO addresses VALUES ('h', ?)", arrayOf(seal("{\"name\":\"Ivan\"}")))
            db.execSQL("INSERT INTO cards VALUES ('c', ?)", arrayOf(seal("4111111111111111")))
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
        DataTransfer.export(context, src, out, password.toCharArray(), srcRing) {}
        return out.toByteArray()
    }

    private fun import(backup: ByteArray, password: String) =
        DataTransfer.import(context, dst, ByteArrayInputStream(backup), backup.size.toLong(), password.toCharArray(), dstRing) {}

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

        // The secrets stay sealed as they were; this side now has the same data keys, under its own device key.
        val v = File(dst, "databases/vault.db")
        val logins = column(v, "logins", "password")
        assertEquals(column(File(src, "databases/vault.db"), "logins", "password"), logins)
        val k = dstRing.key(KeyRing.SECRETS)
        assertEquals("pw-alice", SecretBox.open(k, logins.getValue("a")))
        assertEquals("{\"name\":\"Ivan\"}", SecretBox.open(k, column(v, "addresses", "data").getValue("h")))
        assertEquals("4111111111111111", SecretBox.open(k, column(v, "cards", "number").getValue("c")))
        for (d in KeyRing.DOMAINS) assertArrayEquals(srcRing.key(d).print(), dstRing.key(d).print())
        assertTrue("the key files of this side stay its own", File(dst, "files/keys/vault.json").readText().contains("\"gen\":2"))
        assertFalse(File(dst, "keyring").exists())
    }

    // A backup from before the key ring: no data keys inside, the secrets in the open.
    @Test
    fun oldBackupGetsItsSecretsSealedHere() {
        val db = File(base, "old-vault.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use {
            it.execSQL("CREATE TABLE logins (guid TEXT PRIMARY KEY, password TEXT NOT NULL)")
            it.execSQL("CREATE TABLE addresses (guid TEXT PRIMARY KEY, data TEXT NOT NULL)")
            it.execSQL("CREATE TABLE cards (guid TEXT PRIMARY KEY, number TEXT NOT NULL)")
            it.execSQL("INSERT INTO logins VALUES ('a', 'pw-alice')")
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(BackupCipher.encrypt(out, "secret".toCharArray(), Secure.Argon2)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(("{\"format\":1,\"package\":\"${context.packageName}\"}").toByteArray())
            zip.putNextEntry(ZipEntry("databases/vault.db"))
            zip.write(db.readBytes())
            zip.closeEntry()
        }
        val before = dstRing.key(KeyRing.SECRETS).print()
        import(out.toByteArray(), "secret")
        val stored = column(File(dst, "databases/vault.db"), "logins", "password").getValue("a")
        assertNotEquals("pw-alice", stored)
        assertEquals("pw-alice", SecretBox.open(dstRing.key(KeyRing.SECRETS), stored))
        assertArrayEquals("this side keeps its own keys", before, dstRing.key(KeyRing.SECRETS).print())
    }

    @Test
    fun secureStorageFileSurvivesTheTransfer() {
        val photo = Random(9).nextBytes(2 * VaultFile.CHUNK + 77)
        val key = Ciphers.randomBytes(Cipher256.KEY)
        File(src, "files/vault").mkdirs()
        VaultFile.writer(File(src, "files/vault/7.enc").outputStream(), Cipher256.XCHACHA, CoreKey.import(key.copyOf())).use { it.write(photo) }
        import(export("secret"), "secret")
        val moved = File(dst, "files/vault/7.enc")
        assertArrayEquals(File(src, "files/vault/7.enc").readBytes(), moved.readBytes())
        assertArrayEquals(photo, VaultFile.Reader(Secure.disk(context), moved, CoreKey.import(key.copyOf())).stream().use { it.readBytes() })
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
    fun updateSourcesAndDebugInstallAreNotImported() {
        write(src, "shared_prefs/engines.xml", "<map><string name=\"updateSource\">evil</string></map>".toByteArray())
        write(src, "shared_prefs/antite.xml", ("<map>\n    <boolean name=\"debugInstall\" value=\"true\" />\n" +
            "    <boolean name=\"homeButton\" value=\"true\" />\n</map>").toByteArray())
        write(dst, "shared_prefs/engines.xml", "<map><string name=\"updateSource\">builtin</string></map>".toByteArray())
        import(export("secret"), "secret")
        assertEquals("<map><string name=\"updateSource\">builtin</string></map>",
            File(dst, "shared_prefs/engines.xml").readText())
        val settings = File(dst, "shared_prefs/antite.xml").readText()
        assertFalse(settings.contains("debugInstall"))
        assertTrue(settings.contains("homeButton"))
    }

    @Test
    fun backupWithNewerDatabaseChangesNothing() {
        File(src, "databases").mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(src, "databases/antite.db"), null).use { it.version = 99 }
        val backup = export("secret")
        assertThrows(java.io.IOException::class.java) { import(backup, "secret") }
        assertEquals("old", File(dst, "files/old.txt").readText())
        assertFalse(File(dst, "files/tabs.json").exists())
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

    // Keys never leave the core: the same key seals a fixed text into the same bytes.
    private fun CoreKey.print() = Ciphers.seal(Cipher256.XCHACHA, this, ByteArray(24), "key print".toByteArray(), ByteArray(16))
}
