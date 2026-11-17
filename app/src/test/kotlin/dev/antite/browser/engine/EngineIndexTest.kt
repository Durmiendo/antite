package dev.antite.browser.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class EngineIndexTest {
    private fun artifact(name: String) = """{"url": ["https://example.com/$name"], "size": 1, "sha256": "AB"}"""

    private fun entry(api: Int, minShell: Int, channel: String = "release", abis: List<String> = listOf("arm64-v8a"), type: String = "gecko") =
        """{"id": "e$api$channel", "type": "$type", "api": $api, "channel": "$channel", "version": "1.$api", "minShell": $minShell,
            "adapter": ${artifact("a")}, "upstream": {${abis.joinToString { "\"$it\": ${artifact(it)}" }}}}"""

    private fun index(vararg entries: String) =
        EngineIndex.unsigned("""{"format": 1, "serial": 5, "engines": [${entries.joinToString()}]}""".toByteArray())

    @Test
    fun theFirstEngineThatFitsIsChosen() {
        val i = index(entry(api = 5, minShell = 20), entry(api = 4, minShell = 10), entry(api = 4, minShell = 1))
        assertEquals("1.4", i.choose("arm64-v8a", api = 4, shellVersion = 16)!!.version)
        assertEquals(1, i.choose("arm64-v8a", api = 4, shellVersion = 5)!!.minShell)
        assertNull(i.choose("arm64-v8a", api = 5, shellVersion = 16))
    }

    @Test
    fun abiChannelAndTypeMustMatch() {
        val i = index(entry(api = 4, minShell = 1, abis = listOf("x86_64")), entry(api = 4, minShell = 1, channel = "beta"),
            entry(api = 4, minShell = 1, type = "other"))
        assertNull(i.choose("arm64-v8a", 4, 16))
        assertNotNull(i.choose("x86_64", 4, 16))
        assertEquals("beta", i.choose("arm64-v8a", 4, 16, channel = "beta")!!.channel)
    }

    @Test
    fun hashesAreKeptLowercaseAndOldFormatsRefused() {
        assertEquals("ab", index(entry(api = 4, minShell = 1)).engines[0].adapter.sha256)
        assertThrows(IndexException::class.java) { EngineIndex.unsigned("""{"format": 2, "serial": 1, "engines": []}""".toByteArray()) }
    }

    // The index in the repository is what phones download: its signature must hold, and every ABI the shell is built for
    // must find an engine in it.
    @Test
    fun thePublishedIndexIsSignedAndServesEveryAbi() {
        val root = File("..")
        val json = File(root, "engines.json").readBytes()
        val sig = File(root, "engines.json.sig").readBytes()
        val pem = File(root, "keys/publisher.pub.pem").readLines().filter { !it.startsWith("-----") }.joinToString("")
        val published = JSONObject(String(json)).getLong("expires") - 1
        val index = EngineIndex.verify(json, sig, EngineIndex.publicKey(pem), lastSerial = 0, now = published)
        val api = index.engines.first().api
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            assertNotNull(abi, index.choose(abi, api, shellVersion = index.shell!!.version))
            assertNotNull(abi, index.shell.apk[abi])
        }
        val tampered = json.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(IndexException::class.java) { EngineIndex.verify(tampered, sig, EngineIndex.publicKey(pem), 0, published) }
    }
}
