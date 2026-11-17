package dev.antite.browser.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.antite.keys.Cipher256
import dev.antite.keys.Ciphers
import dev.antite.keys.KeyCore
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

// The native key core on the device itself: the golden files of :keyring, both AES paths, Argon2id against the
// reference. Before argon2kt was dropped the core matched it on four sets of parameters, the default among them.
@RunWith(AndroidJUnit4::class)
class KeyCoreDeviceTest {
    private fun golden(name: String) = javaClass.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val key = ByteArray(64) { it.toByte() }
    private val nonce = ByteArray(24) { (100 + it).toByte() }
    private val aad = "antite-golden".toByteArray()
    private val plain = "Antite golden 1".toByteArray()

    @Test
    fun goldenFilesOnBothAesPaths() {
        for (c in Cipher256.entries) for (soft in listOf(false, true)) {
            val g = golden("seal-${c.name.lowercase()}.bin")
            assertArrayEquals("$c soft=$soft", g, KeyCore.seal(c.id, key, nonce, aad, plain, 0, plain.size, soft))
            assertArrayEquals("$c soft=$soft", plain, KeyCore.open(c.id, key, nonce, aad, g, 0, g.size, soft))
            assertArrayEquals("$c box", plain, Ciphers.unbox(key, golden("box-${c.name.lowercase()}.bin"), aad))
        }
    }

    @Test
    fun argon2idMatchesTheReference() {
        val got = KeyCore.argon2id("correct horse".toByteArray(), "antite-salt-0001".toByteArray(), 3, 65536, 2, 64)
        assertArrayEquals(hex("1a7f6cec4175818a0ec19bfa8d6d5608f49e59e41a13695c3d02b57e4a3e44eb0718a36da8798088545b4198d1f7c49fd5e5092467b837c80931bb335209070f"), got)
    }
}
