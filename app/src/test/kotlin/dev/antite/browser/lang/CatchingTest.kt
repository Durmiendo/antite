package dev.antite.browser.lang

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CatchingTest {
    @Test
    fun anOrdinaryFailureIsAResult() {
        val r = catching { throw IOException("damaged") }
        assertTrue(r.exceptionOrNull() is IOException)
        assertEquals(5, catching { 5 }.getOrNull())
    }

    @Test(expected = OutOfMemoryError::class)
    fun runningOutOfMemoryIsNotAFailedFile() {
        catching { throw OutOfMemoryError() }
    }

    @Test(expected = CancellationException::class)
    fun cancellationPassesThrough() {
        catching { throw CancellationException("stop") }
    }

    @Test
    fun aCancelledCoroutineDoesNotGoOn() = runBlocking {
        var after = false
        val job = launch {
            catching { delay(10_000) }
            after = true
        }
        delay(50)
        job.cancel()
        job.join()
        assertFalse(after)
        // The old trap, for the record: runCatching swallows the cancellation and the code after it runs.
        var trapped = false
        val old = launch {
            runCatching { delay(10_000) }
            trapped = true
        }
        delay(50)
        old.cancel()
        old.join()
        assertTrue(trapped)
    }
}
