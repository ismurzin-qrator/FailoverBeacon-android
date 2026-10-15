package net.qrator.failoverbeacon.core.call.mapper

import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.state.ExchangeState

internal object ExchangeMapper {
    internal fun toExchange(state: ExchangeState): Exchange = with(state) {
        val results = AttemptClassifier.classify(attempts, stopReason, callFailure)
        Exchange(
            callId = callId,
            request = ExchangeRequest(
                serverAddress = target?.address,
                serverPort = target?.port,
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
            attempts = attempts.zip(results) { attempt, result ->
                ConnectAttempt(
                    peerAddress = attempt.peerAddress,
                    connectNanos = attempt.connectInterval.durationNanos,
                    result = result
                )
            },
            timings = TimingsMapper.toTimings(state),
            durationNanos = TimingsMapper.toDurationNanos(state),
            outcome = OutcomeMapper.toOutcome(state, results)
        )
    }
}
