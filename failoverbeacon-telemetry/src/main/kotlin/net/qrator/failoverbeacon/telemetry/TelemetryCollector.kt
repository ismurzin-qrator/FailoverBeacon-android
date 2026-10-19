package net.qrator.failoverbeacon.telemetry

import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.telemetry.filter.EventFilter
import net.qrator.failoverbeacon.telemetry.filter.Sampler
import net.qrator.failoverbeacon.telemetry.mapper.EventMapper

class TelemetryCollector(
    consentSource: ConsentSource,
    blockedHosts: Set<String>,
    failureSampleRate: Double,
    successSampleRate: Double
) : ExchangeSubscriber {
    private val filter = EventFilter(consentSource, blockedHosts)
    private val sampler = Sampler(failureSampleRate, successSampleRate)
    private val eventMapper = EventMapper()

    override fun onExchange(exchange: Exchange) {
        if (!filter.accepts(exchange) || !sampler.keeps(exchange)) return
        val event = eventMapper.toEvent(exchange, sampler.fraction(exchange))
        SdkRegistry.logger.debug { "Telemetry event $event" }
    }
}
