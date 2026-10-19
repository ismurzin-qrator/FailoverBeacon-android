package net.qrator.failoverbeacon.telemetry

import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeConnection
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.telemetry.mapper.EventMapper
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent

const val MS = 1_000_000L

fun exchange(
    callId: Long = 7,
    request: ExchangeRequest = ExchangeRequest("a.com", 443, "GET", bodyBytes = null),
    response: ExchangeResponse? = ExchangeResponse(200, contentLength = null, bodyBytes = 0),
    connection: ExchangeConnection? =
        ExchangeConnection("1.1.1.1", "h2", ProxyType.DIRECT, tls = null),
    dnsAddresses: List<String>? = null,
    attempts: List<ConnectAttempt> = emptyList(),
    timings: ExchangeTimings = ExchangeTimings(null, null, null, null, null, null),
    durationNanos: Long = 60 * MS,
    outcome: ExchangeOutcome = ExchangeOutcome.Ok
) = Exchange(
    callId = callId,
    request = request,
    response = response,
    resendCount = 1,
    connection = connection,
    reusedConnection = false,
    dnsAddresses = dnsAddresses,
    attempts = attempts,
    timings = timings,
    durationNanos = durationNanos,
    outcome = outcome
)

fun attempt(
    ip: String,
    result: ConnectResult,
    connectNanos: Long?,
    proxy: ProxyType = ProxyType.DIRECT
) = ConnectAttempt(ip, proxy, connectNanos, result)

internal fun event(exchange: Exchange = exchange()): TelemetryEvent =
    EventMapper().toEvent(exchange)
