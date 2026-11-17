package dev.antite.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class SourcesTest {
    @Test
    fun plainHttpOnlyToThisDevice() {
        assertTrue(Sources.acceptsUrl("https://example.com/engines.json"))
        assertTrue(Sources.acceptsUrl("http://localhost:8080/engines.json"))
        assertTrue(Sources.acceptsUrl("http://127.0.0.1:8080/engines.json"))
        assertFalse(Sources.acceptsUrl("http://example.com/engines.json"))
        assertFalse(Sources.acceptsUrl("ftp://example.com/engines.json"))
    }

    // Everything before @ is a user name: the request would go to the host after it, over plain HTTP.
    @Test
    fun aUserNameDoesNotPassForLocalhost() {
        assertFalse(Sources.acceptsUrl("http://localhost:80@evil.example/engines.json"))
        assertFalse(Sources.acceptsUrl("http://127.0.0.1:1@evil.example/engines.json"))
        assertFalse(Sources.acceptsUrl("http://localhost:8080.evil.example/engines.json"))
    }

    @Test
    fun keysArePemOrBase64OfP256() {
        val pem = File("../keys/publisher.pub.pem").readText()
        val bare = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
        assertEquals(bare, Sources.parseKey(pem))
        assertEquals(bare, Sources.parseKey(bare))
        assertEquals(32, Sources.fingerprint(bare).split(":").size)
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair().public
        assertThrows(SourceException::class.java) { Sources.parseKey(Base64.getEncoder().encodeToString(rsa.encoded)) }
        assertThrows(SourceException::class.java) { Sources.parseKey("not a key") }
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.genKeyPair().public
        assertThrows(SourceException::class.java) { Sources.parseKey(Base64.getEncoder().encodeToString(p384.encoded)) }
    }
}
