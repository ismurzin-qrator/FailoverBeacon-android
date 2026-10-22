package net.qrator.failoverbeacon.telemetry

import java.util.concurrent.Executor
import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.DataOwner
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.storage.EventStorage
import net.qrator.failoverbeacon.telemetry.filter.EventFilter
import net.qrator.failoverbeacon.telemetry.filter.Sampler
import net.qrator.failoverbeacon.telemetry.mapper.TelemetryEventMapper
import net.qrator.failoverbeacon.telemetry.serialization.TelemetryEventSerializer

class TelemetryCollector(
    consentSource: ConsentSource,
    blockedHosts: Set<String>,
    failureSampleRate: Double,
    successSampleRate: Double,
    private val executor: Executor,
    private val storage: EventStorage
) : ExchangeSubscriber,
    DataOwner {
    private val eventMapper = TelemetryEventMapper()
    private val filter = EventFilter(consentSource, blockedHosts)
    private val sampler = Sampler(failureSampleRate, successSampleRate)
    private val serializer = TelemetryEventSerializer()

    override fun onExchange(exchange: Exchange) {
        executor.execute { collect(exchange) }
    }

    override fun onConsentChanged(consent: TrackingConsent) {
        if (consent == TrackingConsent.NOT_GRANTED) clearData()
    }

    override fun clearData() {
        executor.execute { storage.clear() }
    }

    private fun collect(exchange: Exchange) {
        val event = eventMapper.toEvent(exchange)
        if (!filter.accepts(event)) return
        val samplingFraction = sampler.sample(event) ?: return
        val json = serializer.toJson(event, samplingFraction)
        storage.write(json)
        SdkRegistry.logger.debug { "Telemetry event $json" }
    }
}
