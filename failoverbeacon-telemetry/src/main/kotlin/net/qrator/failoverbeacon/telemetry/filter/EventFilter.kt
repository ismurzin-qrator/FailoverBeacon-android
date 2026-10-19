package net.qrator.failoverbeacon.telemetry.filter

import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.model.Exchange

internal class EventFilter(
    private val consentSource: ConsentSource,
    private val blockedHosts: Set<String>,
    private val sampler: Sampler
) {
    fun accepts(exchange: Exchange): Boolean =
        consentSource.consent != TrackingConsent.NOT_GRANTED &&
            !isBlocked(exchange.request.serverAddress) &&
            sampler.keeps(exchange)

    private fun isBlocked(host: String?): Boolean = host != null && host in blockedHosts
}
