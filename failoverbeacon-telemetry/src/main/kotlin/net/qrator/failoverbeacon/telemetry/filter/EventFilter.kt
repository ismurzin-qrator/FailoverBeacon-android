package net.qrator.failoverbeacon.telemetry.filter

import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent

internal class EventFilter(
    private val consentSource: ConsentSource,
    private val blockedHosts: Set<String>
) {
    fun accepts(event: TelemetryEvent): Boolean =
        consentSource.consent != TrackingConsent.NOT_GRANTED && event.host !in blockedHosts
}
