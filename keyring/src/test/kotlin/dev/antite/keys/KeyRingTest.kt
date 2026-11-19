package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class KeyRingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // A stand-in for the Keystore: a key per alias that can be destroyed for good.
    private class FakeDevice : KeyRing.DeviceKeys {
        val keys = HashMap<String, CoreKey>()
        override fun wrap(alias: String, plain: ByteArray) =
            Ciphers.box(Cipher256.AES, keys.getOrPut(alias) { CoreKey.random() }, plain)
        override fun unwrap(alias: String, blob: ByteArray) =
            Ciphers.unbox(keys[alias] ?: throw IOException("no key $alias"), blob)
        override fun destroy(alias: String) {
            keys.remove(alias)?.close()
        }
    }

    private val device = FakeDevice()
    private val seen = ArrayList<ByteArray>()
    private val kdf = KeyRing.Kdf { password, salt, _ ->
        seen += password
        CoreKey.import(MessageDigest.getInstance("SHA-512").digest(password + salt))
    }

    private fun ring(dir: File = tmp.root) = KeyRing(RealDisk(tmp.root), dir, device, kdf)
    private fun pw(s: String) = s.toByteArray()

    @Test
    fun deviceKeysSurviveARestartWithoutAPassword() {
        val r = ring().apply { create() }
        val v = r.key(KeyRing.VAULT).print()
        assertFalse(v.contentEquals(r.key(KeyRing.SECRETS).print()))
        r.lock()
        assertArrayEquals(v, ring().key(KeyRing.VAULT).print())
    }

    @Test
    fun aPasswordIsNeededAfterLockAndIsWiped() {
        val r = ring().apply { create() }
        val v = r.key(KeyRing.VAULT).print()
        r.setMode(KeyRing.Mode.PASSWORD, pw("correct horse"))
        assertTrue(seen.all { p -> p.all { it == 0.toByte() } })
        r.lock()

        val again = ring()
        assertEquals(KeyRing.Mode.PASSWORD, again.mode)
        assertThrows(KeyRing.LockedException::class.java) { again.key(KeyRing.VAULT) }
        assertThrows(KeyRing.WrongPasswordException::class.java) { again.unlock(pw("wrong")) }
        again.unlock(pw("correct horse"))
        assertArrayEquals(v, again.key(KeyRing.VAULT).print())
        assertTrue(seen.all { p -> p.all { it == 0.toByte() } })
    }

    @Test
    fun passwordOnlyDoesNotNeedTheDeviceKey() {
        val r = ring().apply { create() }
        r.setMode(KeyRing.Mode.PASSWORD, pw("p"))
        r.lock()
        device.keys.clear()
        ring().apply { unlock(pw("p")) }.key(KeyRing.SECRETS)
    }

    @Test
    fun bothNeedsTheDeviceKeyToo() {
        val r = ring().apply { create() }
        r.setMode(KeyRing.Mode.BOTH, pw("p"))
        r.lock()
        device.keys.clear()
        val again = ring().apply { unlock(pw("p")) }
        assertThrows(IOException::class.java) { again.key(KeyRing.VAULT) }
    }

    @Test
    fun backToDeviceForgetsThePassword() {
        val r = ring().apply { create() }
        val v = r.key(KeyRing.VAULT).print()
        r.setMode(KeyRing.Mode.BOTH, pw("p"))
        r.setMode(KeyRing.Mode.DEVICE, null)
        assertFalse(File(tmp.root, "password.json").exists())
        r.lock()
        assertArrayEquals(v, ring().key(KeyRing.VAULT).print())
    }

    @Test
    fun rotationDestroysTheOldDeviceKey() {
        val r = ring().apply { create() }
        val oldFile = File(tmp.root, "vault.json").readText()
        val old = r.key(KeyRing.VAULT).print()
        var seenOld: ByteArray? = null
        r.rotate(KeyRing.VAULT) { o, _ -> seenOld = o.print() }
        assertArrayEquals(old, seenOld)
        val fresh = r.key(KeyRing.VAULT).print()
        assertFalse(fresh.contentEquals(old))
        assertEquals(setOf("antite-vault-2", "antite-secrets-1"), device.keys.keys)

        // A copy of the old key file left on the disk is of no use any more.
        File(tmp.root, "vault.json").writeText(oldFile)
        assertThrows(IOException::class.java) { ring().key(KeyRing.VAULT) }
    }

    @Test
    fun aStopInsideRotationKeepsWhicheverKeyTheDataIsUnder() {
        for (rewritten in listOf(false, true)) {
            val dir = tmp.newFolder()
            val r = ring(dir).apply { create() }
            val old = r.key(KeyRing.VAULT).print()
            var fresh: ByteArray? = null
            runCatching {
                r.rotate(KeyRing.VAULT) { _, n ->
                    fresh = n.print()
                    if (!rewritten) throw IOException("stopped before the rewrite")
                    throw IOException("stopped after the rewrite")
                }
            }
            val data = if (rewritten) fresh!! else old
            val again = ring(dir)
            assertTrue(again.recovering)
            again.recover(KeyRing.VAULT) { it.print().contentEquals(data) }
            assertFalse(again.recovering)
            assertArrayEquals(data, again.key(KeyRing.VAULT).print())
            assertArrayEquals(data, ring(dir).key(KeyRing.VAULT).print())
        }
    }

    @Test
    fun lockClosesTheKeysItHeld() {
        val r = ring().apply { create() }
        val held = r.key(KeyRing.VAULT)
        r.lock()
        assertTrue(held.closed)
    }

    // Rings made before the history domain get it on first use, under the mode the user chose, not the device alone.
    @Test
    fun aLaterDomainIsMadeOnFirstUseUnderTheCurrentMode() {
        val r = ring().apply { create() }
        assertFalse(File(tmp.root, "history.json").exists())
        r.setMode(KeyRing.Mode.PASSWORD, pw("p"))
        r.lock()
        File(tmp.root, "history.json").delete()
        val again = ring()
        assertTrue(again.exists)
        assertThrows(KeyRing.LockedException::class.java) { again.key(KeyRing.HISTORY) }
        assertFalse(File(tmp.root, "history.json").exists())
        again.unlock(pw("p"))
        val h = again.key(KeyRing.HISTORY).print()
        assertEquals(KeyRing.Mode.PASSWORD.id, org.json.JSONObject(File(tmp.root, "history.json").readText()).getInt("mode"))
        again.lock()
        device.keys.clear()
        assertArrayEquals(h, ring().apply { unlock(pw("p")) }.key(KeyRing.HISTORY).print())
    }

    @Test
    fun aBackupKeyForAMissingDomainIsInstalled() {
        val r = ring().apply { create() }
        val k = CoreKey.random()
        val printed = k.print()
        r.install(KeyRing.HISTORY, k)
        assertTrue("install takes the key", k.closed)
        r.lock()
        assertArrayEquals(printed, ring().key(KeyRing.HISTORY).print())
    }

    // Leaving the app locks the ring from the main thread while a background thread may be sealing with a key: the lock
    // waits for the work, and only then wipes the key.
    @Test
    fun lockWaitsForWorkWithAKey() {
        val r = ring().apply { create() }
        val started = java.util.concurrent.CountDownLatch(1)
        var seen: ByteArray? = null
        val worker = Thread {
            r.withKey(KeyRing.VAULT) { k ->
                started.countDown()
                Thread.sleep(300)
                seen = k.print()
            }
        }
        val before = r.key(KeyRing.VAULT).print()
        worker.start()
        started.await()
        r.lock()
        worker.join()
        assertArrayEquals(before, seen)
    }

    @Test
    fun keyFilesAreReplacedWhole() {
        val r = ring().apply { create() }
        r.setMode(KeyRing.Mode.BOTH, pw("p"))
        assertFalse(File(tmp.root, "vault.json.tmp").exists())
        assertFalse(File(tmp.root, "password.json.tmp").exists())
        assertTrue(org.json.JSONObject(File(tmp.root, "vault.json").readText()).getInt("gen") > 1)
    }

    // Rings written before the key core: the device store wrapped the data key itself (or its password box). They open,
    // and each file is written again in version 2 on first use, holding the same key.
    @Test
    fun version1FilesOpenAndAreWrittenAgain() {
        for (mode in KeyRing.Mode.entries) {
            val dir = tmp.newFolder()
            val b64 = java.util.Base64.getEncoder()
            val salt = Ciphers.randomBytes(16)
            val kekRaw = MessageDigest.getInstance("SHA-512").digest(pw("p") + salt)
            val raws = KeyRing.DOMAINS.associateWith { Ciphers.randomBytes(Cipher256.KEY) }
            if (mode.usesPassword) {
                File(dir, "password.json").writeText(org.json.JSONObject().put("salt", b64.encodeToString(salt)).put("t", 1)
                    .put("m", 64).put("p", 1).put("check", b64.encodeToString(boxRaw(Cipher256.AES, kekRaw,
                        "antite-password-check".toByteArray(), "antite-password-check".toByteArray()))).toString())
            }
            for ((d, raw) in raws) {
                var blob = raw
                if (mode.usesPassword) blob = boxRaw(Cipher256.AES, kekRaw, blob, d.name.toByteArray())
                if (mode.usesDevice) blob = device.wrap("antite-$d-1", blob)
                File(dir, "$d.json").writeText(org.json.JSONObject().put("mode", mode.id).put("gen", 1).put("key", b64.encodeToString(blob)).toString())
            }
            val r = ring(dir)
            if (mode.usesPassword) r.unlock(pw("p"))
            assertTrue("$mode", r.needsRewrap())
            for ((d, raw) in raws) {
                assertArrayEquals("$mode $d", keyOf(raw).use { it.print() }, r.key(d).print())
                assertEquals("$mode $d", 2, org.json.JSONObject(File(dir, "$d.json").readText()).getInt("v"))
            }
            r.lock()
            val again = ring(dir).apply { if (mode.usesPassword) unlock(pw("p")) }
            for ((d, raw) in raws) assertArrayEquals("$mode $d again", keyOf(raw).use { it.print() }, again.key(d).print())
        }
    }
}
