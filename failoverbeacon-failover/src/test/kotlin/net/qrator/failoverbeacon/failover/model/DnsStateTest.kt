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
        assertEquals(listOf(ip(1)), answered.addresses)
        assertEquals(listOf(true, false, false), beforeInterval)
        assertFalse(answered.isFresh(timer))
        assertTrue(answered.isAnswerNeeded(timer))
        assertTrue(answered.isStale(timer))
    }

    @Test
    fun `a new answer replaces the addresses and their age`() {
        val state = answered.withAnswer(listOf(ip(2)), atNanos = 90)
        now = 150

        assertEquals(listOf(ip(2)), state.addresses)
        assertTrue(state.isFresh(timer))
    }

    @Test
    fun `after a failure nothing waits for an answer and it is stale after the interval`() {
        val failed = answered.withFailure(atNanos = 200)
        now = 200
        val afterFailure = failed.isStale(timer)
        val answerNeededAfterFailure = failed.isAnswerNeeded(timer)
        now = 199 + INTERVAL
        val beforeInterval = failed.isStale(timer)
        now = 200 + INTERVAL

        assertEquals(listOf(ip(1)), failed.addresses)
        assertTrue(failed.hasAnswer)
        assertFalse(failed.isFresh(timer))
        assertFalse(afterFailure)
        assertFalse(answerNeededAfterFailure)
        assertFalse(beforeInterval)
        assertTrue(failed.isStale(timer))
        assertFalse(failed.isAnswerNeeded(timer))
    }

    @Test
    fun `a failure leaves an empty state without an answer`() {
        assertFalse(DnsState().withFailure(atNanos = 0).hasAnswer)
    }

    @Test
    fun `an answer ends the failure`() {
        val recovered = answered.withFailure(atNanos = 200).withAnswer(listOf(ip(1)), atNanos = 300)
        now = 300 + INTERVAL

        assertTrue(recovered.isAnswerNeeded(timer))
        assertTrue(recovered.isStale(timer))
    }

    private companion object {
        const val INTERVAL = 100L
    }
}
