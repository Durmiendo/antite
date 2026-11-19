package dev.antite.browser.lang

import kotlin.coroutines.cancellation.CancellationException

// The standard catch-all Result builder without its two traps (decision 0017): a cancelled coroutine stays cancelled, and an Error such as
// OutOfMemoryError is not turned into an ordinary failure. Where running out of memory has a meaning of its own
// (a picture too big to decode), the caller catches OutOfMemoryError by name.
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

// A picture that does not decode, or does not fit in the memory left, is shown as no picture.
inline fun <T> decodedOrNull(block: () -> T): T? = try {
    catching(block).getOrNull()
} catch (e: OutOfMemoryError) {
    null
}
