package net.qrator.failoverbeacon.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SdkFailuresTest {
    private val handled = mutableListOf<Throwable>()

    @Test
    fun `passes an exception to the handler`() {
        val failure = IllegalStateException()

        catchSdkFailure(handled::add) { throw failure }

        assertSame(failure, handled.single())
    }

    @Test
    fun `passes a linkage error to the handler`() {
        val failure = NoSuchMethodError()

        catchSdkFailure(handled::add) { throw failure }

        assertSame(failure, handled.single())
    }

    @Test
    fun `lets other errors through`() {
        val failure = AssertionError()

        val thrown = assertFailsWith<AssertionError> {
            catchSdkFailure(handled::add) { throw failure }
        }

        assertSame(failure, thrown)
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `runs the block and skips the handler when nothing fails`() {
        var runs = 0

        catchSdkFailure(handled::add) { runs++ }

        assertEquals(1, runs)
        assertTrue(handled.isEmpty())
    }
}
