package net.qrator.failoverbeacon.telemetry

import java.util.concurrent.Executor
import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.telemetry.filter.EventFilter
import net.qrator.failoverbeacon.telemetry.filter.Sampler
import net.qrator.failoverbeacon.telemetry.mapper.TelemetryEventMapper

class TelemetryCollector(
    consentSource: ConsentSource,
    blockedHosts: Set<String>,
    failureSampleRate: Double,
    successSampleRate: Double,
    private val executor: Executor
) : ExchangeSubscriber {
    private val eventMapper = TelemetryEventMapper()
    private val filter = EventFilter(consentSource, blockedHosts)
    private val sampler = Sampler(failureSampleRate, successSampleRate)

    override fun onExchange(exchange: Exchange) {
        executor.execute { collect(exchange) }
    }

    private fun collect(exchange: Exchange) {
        val event = eventMapper.toEvent(exchange)
        if (!filter.accepts(event)) return
        val samplingFraction = sampler.sample(event) ?: return
        SdkRegistry.logger.debug { "Telemetry event $event, samplingFraction=$samplingFraction" }
    }
}
