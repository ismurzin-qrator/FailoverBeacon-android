package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeConnection
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.core.call.model.ProxyType as ExchangeProxyType
import net.qrator.failoverbeacon.telemetry.model.AttemptResult
import net.qrator.failoverbeacon.telemetry.model.ErrorType
import net.qrator.failoverbeacon.telemetry.model.EventAttempt
import net.qrator.failoverbeacon.telemetry.model.EventTimings
import net.qrator.failoverbeacon.telemetry.model.EventTls
import net.qrator.failoverbeacon.telemetry.model.Phase
import net.qrator.failoverbeacon.telemetry.model.ProxyType
import net.qrator.failoverbeacon.telemetry.model.Step
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent

class EventMapperTest {
    private val mapper = EventMapper()

    @Test
    fun `maps every field of a response`() {
        val exchange = exchange(
            request = ExchangeRequest("a.com", 443, "POST", bodyBytes = 3),
            response = ExchangeResponse(200, contentLength = 5, bodyBytes = 5),
            connection = ExchangeConnection("1.1.1.1", "h2", ExchangeProxyType.HTTP, TLS),
            dnsAddresses = listOf("1.1.1.1", "1.1.1.2"),
            attempts = listOf(
                attempt(
                    "1.1.1.1",
                    ConnectResult.Connected,
                    connectNanos = 20 * MS,
                    ExchangeProxyType.HTTP
                )
            ),
            timings = ExchangeTimings(1 * MS, 20 * MS, 12 * MS, 2 * MS, 30 * MS, 4 * MS)
        )

        val expected = TelemetryEvent(
            callId = 7,
            resendCount = 1,
            host = "a.com",
            port = 443,
            method = "POST",
            phase = Phase.APPLICATION,
            type = ErrorType.OK,
            step = null,
            statusCode = 200,
            protocol = "h2",
            serverIp = "1.1.1.1",
            proxy = ProxyType.HTTP,
            tls = EventTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA"),
            reusedConnection = false,
            dnsAddresses = listOf("1.1.1.1", "1.1.1.2"),
            elapsedMs = 60,
            timings = EventTimings(1, 20, 12, 2, 30, 4),
            requestBodyBytes = 3,
            responseContentLength = 5,
            responseBodyBytes = 5,
            attempts = listOf(
                EventAttempt("1.1.1.1", ProxyType.HTTP, 20, AttemptResult.CONNECTED, null, null)
            )
        )
        assertEquals(expected, mapper.toEvent(exchange))
    }

    @Test
    fun `an error status is an HTTP error`() {
        val event = mapper.toEvent(exchange(response = ExchangeResponse(404, null, 0)))

        assertEquals(ErrorType.HTTP_ERROR, event.type)
        assertEquals(Phase.APPLICATION, event.phase)
        assertNull(event.step)
    }

    @Test
    fun `a canceled exchange is abandoned`() {
        val event = mapper.toEvent(exchange(outcome = ExchangeOutcome.Canceled))

        assertEquals(ErrorType.ABANDONED, event.type)
        assertEquals(Phase.APPLICATION, event.phase)
    }

    @Test
    fun `a failure has its step, phase and type`() {
        val untrusted = ConnectResult.Failed(ExchangeStep.TLS, FailureType.CERT_UNTRUSTED)
        val event = mapper.toEvent(
            exchange(
                response = null,
                attempts = listOf(attempt("1.1.1.1", untrusted, connectNanos = 40 * MS)),
                outcome = ExchangeOutcome.Failed(ExchangeStep.TLS, FailureType.CERT_UNTRUSTED)
            )
        )

        assertEquals(Step.TLS, event.step)
        assertEquals(Phase.CONNECTION, event.phase)
        assertEquals(ErrorType.TLS_CERT_AUTHORITY_INVALID, event.type)
        assertEquals(
            EventAttempt(
                "1.1.1.1",
                ProxyType.DIRECT,
                40,
                AttemptResult.FAILED,
                ErrorType.TLS_CERT_AUTHORITY_INVALID,
                Step.TLS
            ),
            event.attempts.single()
        )
    }

    @Test
    fun `a connect the client stopped is canceled, whoever stopped it`() {
        val event = mapper.toEvent(
            exchange(
                attempts = listOf(
                    attempt("1.1.1.1", ConnectResult.Superseded, connectNanos = 300 * MS),
                    attempt("1.1.1.2", ConnectResult.Canceled, connectNanos = null)
                )
            )
        )

        assertEquals(
            listOf(
                EventAttempt("1.1.1.1", ProxyType.DIRECT, 300, AttemptResult.CANCELED, null, null),
                EventAttempt("1.1.1.2", ProxyType.DIRECT, null, AttemptResult.CANCELED, null, null)
            ),
            event.attempts
        )
    }

    @Test
    fun `a follow-up that failed before the client named its target has none`() {
        val event = mapper.toEvent(
            exchange(
                request = ExchangeRequest(null, null, null, null),
                response = null,
                connection = null,
                outcome = ExchangeOutcome.Failed(ExchangeStep.DNS, FailureType.NAME_NOT_RESOLVED)
            )
        )

        assertNull(event.host)
        assertNull(event.port)
        assertNull(event.method)
        assertNull(event.serverIp)
        assertNull(event.proxy)
        assertNull(event.tls)
        assertEquals(ErrorType.DNS_NAME_NOT_RESOLVED, event.type)
        assertEquals(Phase.DNS, event.phase)
    }

    private fun exchange(
        request: ExchangeRequest = ExchangeRequest("a.com", 443, "GET", bodyBytes = null),
        response: ExchangeResponse? = ExchangeResponse(200, contentLength = null, bodyBytes = 0),
        connection: ExchangeConnection? =
            ExchangeConnection("1.1.1.1", "h2", ExchangeProxyType.DIRECT, tls = null),
        dnsAddresses: List<String>? = null,
        attempts: List<ConnectAttempt> = emptyList(),
        timings: ExchangeTimings = ExchangeTimings(null, null, null, null, null, null),
        durationNanos: Long = 60 * MS,
        outcome: ExchangeOutcome = ExchangeOutcome.Ok
    ) = Exchange(
        callId = 7,
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

    private fun attempt(
        ip: String,
        result: ConnectResult,
        connectNanos: Long?,
        proxy: ExchangeProxyType = ExchangeProxyType.DIRECT
    ) = ConnectAttempt(ip, proxy, connectNanos, result)

    private companion object {
        const val MS = 1_000_000L
        val TLS = ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA")
    }
}
