package com.tempobox.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ResultsTest {

    @Test
    fun `success passes through`() {
        assertThat(resultOf { 42 }.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `ordinary failures are captured`() {
        val result = resultOf<Int> { error("boom") }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `CancellationException is rethrown, not captured`() = runTest {
        var rethrown = false
        try {
            resultOf { throw CancellationException("cancelled") }
        } catch (expected: CancellationException) {
            rethrown = true
        }
        assertThat(rethrown).isTrue()
    }

    @Test
    fun `cancellation from suspending code inside the block propagates`() = runTest {
        suspend fun cancels(): Int = throw CancellationException("scope died")
        var rethrown = false
        try {
            resultOf { cancels() }
        } catch (expected: CancellationException) {
            rethrown = true
        }
        assertThat(rethrown).isTrue()
    }
}
