package net.qrator.failoverbeacon.failover.mapper

import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.failover.model.ConnectOutcome

internal class ConnectOutcomeMapper {
    fun toOutcome(result: ConnectResult, isSecure: Boolean): ConnectOutcome? = when (result) {
        ConnectResult.Connected ->
            if (isSecure) ConnectOutcome.CONNECTED_SECURE else ConnectOutcome.CONNECTED_PLAIN

        is ConnectResult.Failed -> when {
            result.type == null -> null
            result.step == ExchangeStep.TLS -> ConnectOutcome.TLS_FAILED
            else -> ConnectOutcome.TCP_FAILED
        }

        ConnectResult.Superseded, ConnectResult.Canceled -> null
    }
}
