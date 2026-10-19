package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.telemetry.model.AttemptResult
import net.qrator.failoverbeacon.telemetry.model.ErrorType
import net.qrator.failoverbeacon.telemetry.model.EventAttempt
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent

internal class EventMapper {
    private val errorTypeMapper = ErrorTypeMapper()
    private val phaseMapper = PhaseMapper()
    private val stepMapper = StepMapper()
    private val timingsMapper = TimingsMapper()

    fun toEvent(exchange: Exchange): TelemetryEvent {
        val outcome = exchange.outcome
        val failedStep = (outcome as? ExchangeOutcome.Failed)?.step
        val statusCode = exchange.response?.statusCode
        return TelemetryEvent(
            callId = exchange.callId,
            resendCount = exchange.resendCount,
            host = exchange.request.serverAddress,
            port = exchange.request.serverPort,
            method = exchange.request.method,
            phase = phaseMapper.toPhase(failedStep),
            type = toErrorType(outcome, statusCode),
            step = failedStep?.let(stepMapper::toStep),
            statusCode = statusCode,
            protocol = exchange.connection?.protocol,
            serverIp = exchange.connection?.peerAddress,
            reusedConnection = exchange.reusedConnection,
            elapsedMs = timingsMapper.toMillis(exchange.durationNanos),
            timings = timingsMapper.toTimings(exchange.timings),
            requestBodyBytes = exchange.request.bodyBytes,
            responseContentLength = exchange.response?.contentLength,
            responseBodyBytes = exchange.response?.bodyBytes,
            attempts = exchange.attempts.map(::toAttempt)
        )
    }

    private fun toErrorType(outcome: ExchangeOutcome, statusCode: Int?): ErrorType =
        when (outcome) {
            ExchangeOutcome.Ok ->
                if (statusCode != null && statusCode >= 400) ErrorType.HTTP_ERROR else ErrorType.OK

            ExchangeOutcome.Canceled -> ErrorType.ABANDONED

            is ExchangeOutcome.Failed -> errorTypeMapper.toErrorType(outcome.step, outcome.type)
        }

    private fun toAttempt(attempt: ConnectAttempt): EventAttempt {
        val failed = attempt.result as? ConnectResult.Failed
        return EventAttempt(
            ip = attempt.peerAddress,
            ms = attempt.connectNanos?.let(timingsMapper::toMillis),
            result = when (attempt.result) {
                ConnectResult.Connected -> AttemptResult.CONNECTED
                is ConnectResult.Failed -> AttemptResult.FAILED
                ConnectResult.Superseded, ConnectResult.Canceled -> AttemptResult.CANCELED
            },
            type = failed?.let { errorTypeMapper.toErrorType(it.step, it.type) },
            step = failed?.step?.let(stepMapper::toStep)
        )
    }
}
