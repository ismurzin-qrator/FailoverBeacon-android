package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.FailoverMark
import net.qrator.failoverbeacon.telemetry.model.TelemetryFailover

internal class TelemetryFailoverMapper {
    fun toFailover(mark: FailoverMark): TelemetryFailover = when (mark) {
        FailoverMark.ON -> TelemetryFailover.ON
        FailoverMark.DNS_FAILED -> TelemetryFailover.DNS_FAILED
        FailoverMark.RETRY -> TelemetryFailover.RETRY
    }
}
