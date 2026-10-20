package net.qrator.failoverbeacon.core.call.mapper

import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.state.ExchangeState

internal class ExchangeMapper(failureMapper: FailureMapper) {
    private val attemptMapper = AttemptMapper(failureMapper)
    private val outcomeMapper = OutcomeMapper(failureMapper)
    private val timingsMapper = TimingsMapper()

    fun toExchange(state: ExchangeState): Exchange = with(state) {
        val results = attemptMapper.toResults(attempts, stopReason, callFailure)
        Exchange(
            callId = callId,
            request = ExchangeRequest(
                scheme = target?.scheme,
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
            connection = connection,
            reusedConnection = reusedConnection,
            dnsAddresses = dnsAddresses,
            attempts = attempts.zip(results) { attempt, result ->
                ConnectAttempt(
                    peerAddress = attempt.peerAddress,
                    proxy = attempt.proxy,
                    connectNanos = attempt.connectInterval.durationNanos,
                    result = result
                )
            },
            timings = timingsMapper.toTimings(state),
            durationNanos = timingsMapper.toDurationNanos(state),
            outcome = outcomeMapper.toOutcome(state, results)
        )
    }
}
