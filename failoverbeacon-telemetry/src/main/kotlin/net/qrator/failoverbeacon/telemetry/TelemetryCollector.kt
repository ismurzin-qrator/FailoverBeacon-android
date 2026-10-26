package net.qrator.failoverbeacon.telemetry

import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.DataOwner
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.telemetry.filter.EventFilter
import net.qrator.failoverbeacon.telemetry.filter.Sampler
import net.qrator.failoverbeacon.telemetry.mapper.TelemetryEventMapper
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent
import net.qrator.failoverbeacon.telemetry.serialization.TelemetryEventSerializer
import net.qrator.failoverbeacon.telemetry.storage.EventStorage

internal class TelemetryCollector(
    consentSource: ConsentSource,
    blockedHosts: Set<String>,
    failureSampleRate: Double,
    successSampleRate: Double,
    memoryQueueSize: Int,
    private val executor: Executor,
    private val storage: EventStorage,
    private val dropped: DroppedEvents
) : ExchangeSubscriber,
    DataOwner {
    private val eventMapper = TelemetryEventMapper()
    private val filter = EventFilter(consentSource, blockedHosts)
    private val sampler = Sampler(failureSampleRate, successSampleRate)
    private val serializer = TelemetryEventSerializer()
    private val memoryQueueSlots = Semaphore(memoryQueueSize)

    override fun onExchange(exchange: Exchange) {
        if (!filter.consentAllows()) return
        val event = eventMapper.toEvent(exchange)
        if (!filter.accepts(event)) return
        val samplingFraction = sampler.sample(event) ?: return
        if (!memoryQueueSlots.tryAcquire()) {
            dropped.addToAll(DropReason.MEMORY_OVERFLOW, 1)
            SdkRegistry.logger.debug {
                "[Telemetry] Events dropped: beacons=all, reason=memory_overflow, count=1"
            }
            SdkRegistry.logger.warn(onlyOnce = true) {
                "[Telemetry] Memory queue is full. Events are dropped"
            }
            return
        }
        executor.execute {
            try {
                store(event, samplingFraction)
            } finally {
                memoryQueueSlots.release()
            }
        }
    }

    override fun onConsentChanged(consent: TrackingConsent) {
        if (consent == TrackingConsent.NOT_GRANTED) clearData()
    }

    override fun clearData() {
        executor.execute {
            storage.clear()
            dropped.clear()
        }
    }

    private fun store(event: TelemetryEvent, samplingFraction: Double) {
        if (!filter.consentAllows()) return
        val json = serializer.toJson(event, samplingFraction)
        SdkRegistry.logger.debug { "[Telemetry] Event $json" }
        storage.write(json)
    }
}
