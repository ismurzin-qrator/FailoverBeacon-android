package net.qrator.failoverbeacon.telemetry.filter

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    @Test
    fun `an ok response without a failed connect is a success, anything else a failure`() {
        val rates = Sampler(failureSampleRate = 0.9, successSampleRate = 0.1)
        val superseded = attempt("1.1.1.1", ConnectResult.Superseded, connectNanos = null)
        val refused =
            attempt("1.1.1.1", ConnectResult.Failed(ExchangeStep.TCP, FailureType.REFUSED), 10)

        assertEquals(
            0.1,
            rates.sample(event(exchange(callId = 0, attempts = listOf(superseded))))
        )
        assertEquals(
            0.9,
            rates.sample(event(exchange(callId = 0, attempts = listOf(refused))))
        )
        assertEquals(0.9, rates.sample(failure(callId = 0, ExchangeResponse(404, null, 0))))
        assertEquals(
            0.9,
            rates.sample(
                event(exchange(callId = 0, outcome = ExchangeOutcome.Canceled(step = null)))
            )
        )
    }

    @Test
    fun `one roll per call decides every exchange of it`() {
        // The top bit alone: the roll of this call is 0.5.
        val call = Long.MIN_VALUE
        val rates = Sampler(failureSampleRate = 0.6, successSampleRate = 0.4)

        assertEquals(0.6, rates.sample(failure(call)))
        assertNull(rates.sample(event(exchange(callId = call))))
        assertEquals(1.0, Sampler(1.0, 0.0).sample(failure(callId = -1)))
        assertNull(Sampler(1.0, 0.0).sample(event(exchange(callId = 0))))
    }

    @Test
    fun `keeps about the success rate of random calls`() {
        val sampler = Sampler(failureSampleRate = 0.9, successSampleRate = 0.1)
        val random = Random(42)

        val kept = (1..CALLS).count {
            sampler.sample(event(exchange(callId = random.nextLong()))) != null
        }

        assertTrue(kept in 9_500..10_500, "kept $kept of $CALLS")
    }

    private fun failure(callId: Long, response: ExchangeResponse = ExchangeResponse(500, null, 0)) =
        event(exchange(callId = callId, response = response))

    private companion object {
        const val CALLS = 100_000
    }
}
