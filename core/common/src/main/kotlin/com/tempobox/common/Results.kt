package com.tempobox.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that never swallows coroutine cancellation.
 *
 * A plain `runCatching` around suspending code catches [CancellationException]
 * too, which breaks structured concurrency and logs a routine cancellation as
 * a failure. Use this instead whenever the block (directly or transitively)
 * calls suspend functions.
 */
inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
