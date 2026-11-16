package dev.antite.browser.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class EngineUpdatesTest {
    @Test
    fun indexUpToTheLimitIsRead() {
        val bytes = ByteArray(100) { it.toByte() }
        assertArrayEquals(bytes, EngineUpdates.readLimited(ByteArrayInputStream(bytes), 100))
    }

    @Test
    fun largerAnswerIsRefused() {
        assertThrows(IOException::class.java) { EngineUpdates.readLimited(ByteArrayInputStream(ByteArray(101)), 100) }
    }
}
