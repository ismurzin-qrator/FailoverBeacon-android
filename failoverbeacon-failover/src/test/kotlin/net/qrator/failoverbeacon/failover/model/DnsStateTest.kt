package net.qrator.failoverbeacon.failover.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.ip

class DnsStateTest {
    private var now = 10L
    private val timer = RefreshTimer({ now }, INTERVAL)
    private val answered = DnsState().withAnswer(listOf(ip(1)), atNanos = 10)

    @Test
    fun `an empty state has no answer, needs one and is stale`() {
        val state = DnsState()

        assertFalse(state.hasAnswer)
        assertFalse(state.isFresh(timer))
        assertTrue(state.isAnswerNeeded(timer))
        assertTrue(state.isStale(timer))
    }

    @Test
    fun `an answer is fresh until the interval passes`() {
        now = 9 + INTERVAL
        val beforeInterval = listOf(
            answered.isFresh(timer),
            answered.isAnswerNeeded(timer),
            answered.isStale(timer)
        )
        now = 10 + INTERVAL

        assertTrue(answered.hasAnswer)
        assertEquals(listOf(true, false, false), beforeInterval)
        assertFalse(answered.isFresh(timer))
        assertTrue(answered.isAnswerNeeded(timer))
        assertTrue(answered.isStale(timer))
    }

    @Test
    fun `after a failure nothing waits for an answer and it is stale after the interval`() {
        val failed = answered.withFailure(atNanos = 200)
        now = 199 + INTERVAL
        val beforeInterval = failed.isStale(timer)
        now = 200 + INTERVAL

        assertEquals(listOf(ip(1)), failed.addresses)
        assertFalse(beforeInterval)
        assertTrue(failed.isStale(timer))
        assertFalse(failed.isFresh(timer))
        assertFalse(failed.isAnswerNeeded(timer))
    }

    @Test
    fun `an answer ends the failure`() {
        val recovered = answered.withFailure(atNanos = 200).withAnswer(listOf(ip(1)), atNanos = 300)
        now = 300 + INTERVAL

        assertTrue(recovered.isAnswerNeeded(timer))
    }

    private companion object {
        const val INTERVAL = 100L
    }
}
