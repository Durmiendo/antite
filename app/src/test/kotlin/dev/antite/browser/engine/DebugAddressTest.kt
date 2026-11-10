package dev.antite.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugAddressTest {
    @Test
    fun bareHostGetsDefaultPortAndIndexPath() {
        assertEquals("http://192.168.0.13:8790/engines.json", DebugAddress.indexUrl("192.168.0.13"))
        assertEquals("http://192.168.0.13:8790/engines.json", DebugAddress.indexUrl(" 192.168.0.13/ "))
        assertEquals("http://pc.local:8790/engines.json", DebugAddress.indexUrl("pc.local"))
    }

    @Test
    fun givenPortIsKept() {
        assertEquals("http://192.168.0.13:9000/engines.json", DebugAddress.indexUrl("192.168.0.13:9000"))
    }

    @Test
    fun fullAddressIsKept() {
        assertEquals("http://192.168.0.13:8790/engines.json", DebugAddress.indexUrl("http://192.168.0.13:8790/engines.json"))
        assertEquals("https://example.com/a/engines.json?x=1", DebugAddress.indexUrl("https://example.com/a/engines.json?x=1"))
    }

    @Test
    fun schemeWithoutPortMeansTheSchemePort() {
        assertEquals("http://example.com/engines.json", DebugAddress.indexUrl("http://example.com"))
    }

    @Test
    fun ipv6() {
        assertEquals("http://[fe80::1]:8790/engines.json", DebugAddress.indexUrl("[fe80::1]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyIsRejected() {
        DebugAddress.indexUrl("  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun garbageIsRejected() {
        DebugAddress.indexUrl("192.168 .0.13")
    }

    @Test
    fun hostAndPortForMessages() {
        assertEquals("192.168.0.13:8790", DebugAddress.hostPort("http://192.168.0.13:8790/engines.json"))
        assertEquals("example.com:443", DebugAddress.hostPort("https://example.com/engines.json"))
        assertEquals(443, DebugAddress.port("https://example.com/engines.json"))
    }
}
