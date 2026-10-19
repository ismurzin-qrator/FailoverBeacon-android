package net.qrator.failoverbeacon.telemetry.filter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.telemetry.TestConsentSource
import net.qrator.failoverbeacon.telemetry.event
import net.qrator.failoverbeacon.telemetry.exchange

class EventFilterTest {
    private val consentSource = TestConsentSource(TrackingConsent.GRANTED)
    private val filter = EventFilter(consentSource, blockedHosts = setOf("blocked.com"))

    @Test
    fun `collects with consent granted or pending, never without it`() {
        assertTrue(filter.accepts(event()))

        consentSource.consent = TrackingConsent.PENDING
        assertTrue(filter.accepts(event()))

        consentSource.consent = TrackingConsent.NOT_GRANTED
        assertFalse(filter.accepts(event()))
    }

    @Test
    fun `drops a blocked host only`() {
        assertFalse(filter.accepts(eventTo("blocked.com")))
        assertTrue(filter.accepts(eventTo("sub.blocked.com")))
        assertTrue(filter.accepts(eventTo(null)))
    }

    private fun eventTo(host: String?) =
        event(exchange(request = ExchangeRequest(host, 443, "GET", bodyBytes = null)))
}
