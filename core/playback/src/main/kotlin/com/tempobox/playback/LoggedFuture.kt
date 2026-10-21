package com.tempobox.playback

import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * [future] that logs before failing. Media3 session callbacks swallow a
 * future's exception into a generic error result, so an unlogged failure is
 * invisible. Deliberate control-flow failures ([UnsupportedOperationException],
 * [IllegalArgumentException] — e.g. "nothing to resume", "no embedded artwork")
 * log at I; everything else logs at E. Cancellation passes through untouched.
 */
internal fun <T> CoroutineScope.loggedFuture(
    tag: String,
    what: String,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T,
): ListenableFuture<T> = future(context) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnsupportedOperationException) {
        Log.i(tag, "$what declined: ${e.message}")
        throw e
    } catch (e: IllegalArgumentException) {
        Log.i(tag, "$what declined: ${e.message}")
        throw e
    } catch (e: Exception) {
        Log.e(tag, "$what failed", e)
        throw e
    }
}
