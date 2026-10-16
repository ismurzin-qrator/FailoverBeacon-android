package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.telemetry.model.Phase

internal class PhaseMapper {
    fun toPhase(step: ExchangeStep?): Phase = when (step) {
        ExchangeStep.DNS -> Phase.DNS
        ExchangeStep.TCP, ExchangeStep.TLS -> Phase.CONNECTION
        ExchangeStep.REQUEST, ExchangeStep.RESPONSE, null -> Phase.APPLICATION
    }
}
