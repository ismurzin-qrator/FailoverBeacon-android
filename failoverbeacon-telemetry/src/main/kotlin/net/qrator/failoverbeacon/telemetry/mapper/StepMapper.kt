package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.telemetry.model.Step

internal class StepMapper {
    fun toStep(step: ExchangeStep): Step = when (step) {
        ExchangeStep.DNS -> Step.DNS
        ExchangeStep.TCP -> Step.TCP
        ExchangeStep.TLS -> Step.TLS
        ExchangeStep.REQUEST -> Step.REQUEST
        ExchangeStep.RESPONSE -> Step.RESPONSE
    }
}
