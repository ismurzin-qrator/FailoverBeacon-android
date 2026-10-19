package net.qrator.failoverbeacon.telemetry.filter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.telemetry.exchange

class EventFilterTest {
    private val consentSource = TestConsentSource(TrackingConsent.GRANTED)
    private val filter = EventFilter(
        consentSource,
        blockedHosts = setOf("blocked.com"),
        sampler = Sampler(failureSampleRate = 1.0, successSampleRate = 0.0)
    )

    @Test
    fun `collects with consent granted or pending, never without it`() {
        assertTrue(filter.accepts(failure()))

        consentSource.consent = TrackingConsent.PENDING
        assertTrue(filter.accepts(failure()))

        consentSource.consent = TrackingConsent.NOT_GRANTED
        assertFalse(filter.accepts(failure()))
    }

    @Test
    fun `drops a blocked host only`() {
        assertFalse(filter.accepts(failure(host = "blocked.com")))
        assertTrue(filter.accepts(failure(host = "sub.blocked.com")))
        assertTrue(filter.accepts(failure(host = null)))
    }

    @Test
    fun `drops what the sampler drops`() {
        assertFalse(filter.accepts(exchange()))
    }

    private fun failure(host: String? = "a.com") = exchange(
        request = ExchangeRequest(host, 443, "GET", bodyBytes = null),
        response = ExchangeResponse(500, null, 0)
    )

    private class TestConsentSource(override var consent: TrackingConsent) : ConsentSource
}
