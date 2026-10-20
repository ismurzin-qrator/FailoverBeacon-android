package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.telemetry.model.TelemetryStep

internal class TelemetryStepMapper {
    fun toStep(step: ExchangeStep): TelemetryStep = when (step) {
        ExchangeStep.DNS -> TelemetryStep.DNS
        ExchangeStep.TCP -> TelemetryStep.TCP
        ExchangeStep.TLS -> TelemetryStep.TLS
        ExchangeStep.REQUEST -> TelemetryStep.REQUEST
        ExchangeStep.RESPONSE -> TelemetryStep.RESPONSE
    }
}
