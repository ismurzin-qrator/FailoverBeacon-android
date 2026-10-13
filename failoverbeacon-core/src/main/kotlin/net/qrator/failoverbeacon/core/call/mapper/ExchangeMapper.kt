package net.qrator.failoverbeacon.core.call.mapper

import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.call.state.ExchangeState

internal object ExchangeMapper {
    internal fun toExchange(state: ExchangeState): Exchange = with(state) {
        Exchange(
            request = ExchangeRequest(
                serverAddress = serverAddress,
                serverPort = serverPort,
                method = method,
                bodyBytes = requestBodyBytes
            ),
            response = statusCode?.let {
                ExchangeResponse(
                    statusCode = it,
                    contentLength = contentLength,
                    bodyBytes = responseBodyBytes
                )
            },
            resendCount = resendCount,
            serverIp = serverIp,
            protocol = protocol,
            reusedConnection = reusedConnection,
            attempts = attempts.map {
                ConnectAttempt(
                    peerAddress = it.peerAddress,
                    connectNanos = it.connectInterval.durationNanos,
                    result = classifyAttempt(it)
                )
            },
            timings = ExchangeTimings(
                dnsNanos = dnsInterval.durationNanos,
                connectNanos = connectedAttempt?.connectInterval?.durationNanos,
                tlsNanos = tlsInterval.durationNanos,
                sendNanos = sendInterval.durationNanos,
                waitNanos = waitInterval.durationNanos,
                receiveNanos = receiveInterval.durationNanos
            ),
            durationNanos = totalInterval.durationNanos ?: 0,
            outcome = toOutcome()
        )
    }

    private fun ExchangeState.toOutcome(): ExchangeOutcome = when {
        canceled -> ExchangeOutcome.Canceled
        failure != null -> ExchangeOutcome.Failed(lastStep, failure)
        responded -> ExchangeOutcome.Ok
        else -> ExchangeOutcome.Failed(lastStep, cause = null)
    }
}
