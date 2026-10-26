package net.qrator.failoverbeacon.telemetry.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `the roll of a call is its id's top 53 bits as a fraction`() {
        val half = Long.MIN_VALUE
        val rates = Sampler(failureSampleRate = 0.6, successSampleRate = 0.4)

        assertEquals(0.6, rates.sample(failure(half)))
        assertNull(rates.sample(event(exchange(callId = half))))
    }

    @Test
    fun `a rate of 1 keeps every call, a rate of 0 none`() {
        val sampler = Sampler(failureSampleRate = 1.0, successSampleRate = 0.0)

        assertEquals(1.0, sampler.sample(failure(callId = -1)))
        assertNull(sampler.sample(event(exchange(callId = 0))))
    }

    private fun failure(callId: Long, response: ExchangeResponse = ExchangeResponse(500, null, 0)) =
        event(exchange(callId = callId, response = response))
}
