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
import net.qrator.failoverbeacon.telemetry.event
import net.qrator.failoverbeacon.telemetry.exchange

class SamplerTest {
    private val sampler = Sampler(failureSampleRate = 0.9, successSampleRate = 0.1)

    @Test
    fun `an ok response without a failed connect is a success, anything else a failure`() {
        val superseded = attempt("1.1.1.1", ConnectResult.Superseded, connectNanos = null)
        val refused =
            attempt("1.1.1.1", ConnectResult.Failed(ExchangeStep.TCP, FailureType.REFUSED), 10)

        assertEquals(0.1, sampler.fraction(event(exchange(attempts = listOf(superseded)))))
        assertEquals(0.9, sampler.fraction(event(exchange(attempts = listOf(refused)))))
        assertEquals(
            0.9,
            sampler.fraction(event(exchange(response = ExchangeResponse(404, null, 0))))
        )
        assertEquals(
            0.9,
            sampler.fraction(event(exchange(outcome = ExchangeOutcome.Canceled(step = null))))
        )
    }

    @Test
    fun `one roll per call decides every exchange of it`() {
        // The top bit alone: the roll of this call is 0.5.
        val call = Long.MIN_VALUE
        val rates = Sampler(failureSampleRate = 0.6, successSampleRate = 0.4)

        assertTrue(rates.keeps(failure(call)))
        assertFalse(rates.keeps(event(exchange(callId = call))))
        assertTrue(Sampler(1.0, 0.0).keeps(failure(callId = -1)))
        assertFalse(Sampler(1.0, 0.0).keeps(event(exchange(callId = 0))))
    }

    @Test
    fun `keeps about the success rate of random calls`() {
        val random = Random(42)

        val kept = (1..CALLS).count { sampler.keeps(event(exchange(callId = random.nextLong()))) }

        assertTrue(kept in 9_500..10_500, "kept $kept of $CALLS")
    }

    private fun failure(callId: Long) =
        event(exchange(callId = callId, response = ExchangeResponse(500, null, 0)))

    private companion object {
        const val CALLS = 100_000
    }
}
