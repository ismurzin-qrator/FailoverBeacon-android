package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttempt
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttemptResult
import net.qrator.failoverbeacon.telemetry.model.TelemetryErrorType
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent

internal class TelemetryEventMapper {
    private val errorTypeMapper = TelemetryErrorTypeMapper()
    private val proxyTypeMapper = TelemetryProxyTypeMapper()
    private val stepMapper = TelemetryStepMapper()
    private val timingsMapper = TelemetryTimingsMapper()
    private val tlsMapper = TelemetryTlsMapper()

    fun toEvent(exchange: Exchange): TelemetryEvent {
        val outcome = exchange.outcome
        val failedStep = when (outcome) {
            ExchangeOutcome.Ok -> null
            is ExchangeOutcome.Canceled -> outcome.step
            is ExchangeOutcome.Failed -> outcome.step
        }
        val statusCode = exchange.response?.statusCode
        val connection = exchange.connection
        val type = toErrorType(outcome, exchange.response)
        return TelemetryEvent(
            callId = exchange.callId,
            resendCount = exchange.resendCount,
            host = exchange.request.serverAddress,
            port = exchange.request.serverPort,
            method = exchange.request.method,
            phase = type.phase,
            type = type,
            step = failedStep?.let(stepMapper::toStep),
            statusCode = statusCode,
            protocol = connection?.protocol,
            serverIp = connection?.peerAddress,
            proxy = connection?.proxy?.let(proxyTypeMapper::toProxyType),
            tls = connection?.tls?.let(tlsMapper::toTls),
            reusedConnection = exchange.reusedConnection,
            dnsAddresses = exchange.dnsAddresses,
            elapsedMs = timingsMapper.toMillis(exchange.durationNanos),
            timings = timingsMapper.toTimings(exchange.timings),
            requestBodyBytes = exchange.request.bodyBytes,
            responseContentLength = exchange.response?.contentLength,
            responseBodyBytes = exchange.response?.bodyBytes,
            attempts = exchange.attempts.map(::toAttempt)
        )
    }

    private fun toErrorType(
        outcome: ExchangeOutcome,
        response: ExchangeResponse?
    ): TelemetryErrorType = when (outcome) {
        ExchangeOutcome.Ok ->
            if (response != null && response.statusCode >= MIN_HTTP_ERROR_STATUS) {
                TelemetryErrorType.HTTP_ERROR
            } else {
                TelemetryErrorType.OK
            }

        is ExchangeOutcome.Canceled -> TelemetryErrorType.ABANDONED

        is ExchangeOutcome.Failed ->
            errorTypeMapper.toErrorType(outcome.step, outcome.type, response)
    }

    private fun toAttempt(attempt: ConnectAttempt): TelemetryAttempt {
        val failed = attempt.result as? ConnectResult.Failed
        return TelemetryAttempt(
            ip = attempt.peerAddress,
            proxy = proxyTypeMapper.toProxyType(attempt.proxy),
            ms = attempt.connectNanos?.let(timingsMapper::toMillis),
            result = when (attempt.result) {
                ConnectResult.Connected -> TelemetryAttemptResult.CONNECTED
                is ConnectResult.Failed -> TelemetryAttemptResult.FAILED
                ConnectResult.Superseded, ConnectResult.Canceled -> TelemetryAttemptResult.CANCELED
            },
            type = failed?.let { errorTypeMapper.toErrorType(it.step, it.type, response = null) },
            step = failed?.step?.let(stepMapper::toStep)
        )
    }

    private companion object {
        const val MIN_HTTP_ERROR_STATUS = 400
    }
}
