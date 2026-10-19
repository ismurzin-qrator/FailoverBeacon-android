package net.qrator.failoverbeacon.telemetry.filter

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.telemetry.attempt
import net.qrator.failoverbeacon.telemetry.exchange

class SamplerTest {
    private val sampler = Sampler(failureSampleRate = 0.9, successSampleRate = 0.1)

    @Test
    fun `a response below 400 with no failed connect is a success`() {
        val superseded = attempt("1.1.1.1", ConnectResult.Superseded, connectNanos = null)

        assertEquals(0.1, sampler.fraction(exchange()))
        assertEquals(0.1, sampler.fraction(exchange(attempts = listOf(superseded))))
        assertEquals(0.1, sampler.fraction(exchange(response = ExchangeResponse(399, null, 0))))
    }

    @Test
    fun `everything else is a failure`() {
        val failedConnect = ConnectResult.Failed(ExchangeStep.TCP, FailureType.REFUSED)

        assertEquals(0.9, sampler.fraction(exchange(response = ExchangeResponse(400, null, 0))))
        assertEquals(
            0.9,
            sampler.fraction(exchange(attempts = listOf(attempt("1.1.1.1", failedConnect, 10))))
        )
        assertEquals(0.9, sampler.fraction(exchange(outcome = ExchangeOutcome.Canceled)))
        assertEquals(
            0.9,
            sampler.fraction(
                exchange(response = null, outcome = ExchangeOutcome.Failed(ExchangeStep.DNS, null))
            )
        )
    }

    @Test
    fun `rates of 1 and 0 keep and drop every call`() {
        val all = Sampler(failureSampleRate = 1.0, successSampleRate = 0.0)

        assertTrue(all.keeps(failure(callId = -1)))
        assertFalse(all.keeps(exchange(callId = 0)))
    }

    @Test
    fun `one roll per call keeps its failures and drops its successes alike`() {
        // The top bit alone: the roll of this call is 0.5.
        val call = Long.MIN_VALUE
        val rates = Sampler(failureSampleRate = 0.6, successSampleRate = 0.4)

        assertTrue(rates.keeps(failure(call)))
        assertFalse(rates.keeps(exchange(callId = call)))
        assertTrue(Sampler(0.6, 0.6).keeps(exchange(callId = call)))
        assertFalse(Sampler(0.4, 0.4).keeps(failure(call)))
    }

    @Test
    fun `keeps about the success rate of random calls`() {
        val random = Random(42)

        val kept = (1..CALLS).count { sampler.keeps(exchange(callId = random.nextLong())) }

        assertTrue(kept in 9_500..10_500, "kept $kept of $CALLS")
    }

    private fun failure(callId: Long) =
        exchange(callId = callId, response = ExchangeResponse(500, null, 0))

    private companion object {
        const val CALLS = 100_000
    }
}
