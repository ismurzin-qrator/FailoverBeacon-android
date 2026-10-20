package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.telemetry.MS
import net.qrator.failoverbeacon.telemetry.attempt
import net.qrator.failoverbeacon.telemetry.exchange
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttempt
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttemptResult
import net.qrator.failoverbeacon.telemetry.model.TelemetryErrorType
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent
import net.qrator.failoverbeacon.telemetry.model.TelemetryPhase
import net.qrator.failoverbeacon.telemetry.model.TelemetryProxyType
import net.qrator.failoverbeacon.telemetry.model.TelemetryStep
import net.qrator.failoverbeacon.telemetry.model.TelemetryTimings
import net.qrator.failoverbeacon.telemetry.model.TelemetryTls

class TelemetryEventMapperTest {
    private val mapper = TelemetryEventMapper()

    @Test
    fun `maps every field of a response`() {
        val exchange = exchange(
            request = ExchangeRequest("a.com", 443, "POST", bodyBytes = 3),
            response = ExchangeResponse(200, contentLength = 5, bodyBytes = 5),
            connection = ExchangeConnection("1.1.1.1", "h2", ProxyType.HTTP, TLS),
            dnsAddresses = listOf("1.1.1.1", "1.1.1.2"),
            attempts = listOf(
                attempt(
                    "1.1.1.1",
                    ConnectResult.Connected,
                    connectNanos = 20 * MS,
                    ProxyType.HTTP
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
            phase = TelemetryPhase.APPLICATION,
            type = TelemetryErrorType.OK,
            step = null,
            statusCode = 200,
            protocol = "h2",
            serverIp = "1.1.1.1",
            proxy = TelemetryProxyType.HTTP,
            tls = TelemetryTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA"),
            reusedConnection = false,
            dnsAddresses = listOf("1.1.1.1", "1.1.1.2"),
            elapsedMs = 60,
            timings = TelemetryTimings(1, 20, 12, 2, 30, 4),
            requestBodyBytes = 3,
            responseContentLength = 5,
            responseBodyBytes = 5,
            attempts = listOf(
                TelemetryAttempt(
                    "1.1.1.1",
                    TelemetryProxyType.HTTP,
                    20,
                    TelemetryAttemptResult.CONNECTED,
                    null,
                    null
                )
            )
        )
        assertEquals(expected, mapper.toEvent(exchange))
    }

    @Test
    fun `an error status is an HTTP error`() {
        val event = mapper.toEvent(exchange(response = ExchangeResponse(404, null, 0)))

        assertEquals(TelemetryErrorType.HTTP_ERROR, event.type)
        assertEquals(TelemetryPhase.APPLICATION, event.phase)
        assertNull(event.step)
    }

    @Test
    fun `a canceled exchange is abandoned at its step`() {
        val event = mapper.toEvent(exchange(outcome = ExchangeOutcome.Canceled(ExchangeStep.TLS)))

        assertEquals(TelemetryErrorType.ABANDONED, event.type)
        assertEquals(TelemetryPhase.APPLICATION, event.phase)
        assertEquals(TelemetryStep.TLS, event.step)
    }

    @Test
    fun `a failure in the response keeps its step and takes the phase of its type`() {
        val cutBody = ExchangeResponse(200, contentLength = 100, bodyBytes = null)

        val reset = mapper.toEvent(
            exchange(
                response = cutBody,
                outcome = ExchangeOutcome.Failed(ExchangeStep.RESPONSE, FailureType.RESET)
            )
        )
        val closed = mapper.toEvent(
            exchange(
                response = cutBody,
                outcome = ExchangeOutcome.Failed(ExchangeStep.RESPONSE, FailureType.CLOSED)
            )
        )

        assertEquals(TelemetryStep.RESPONSE, reset.step)
        assertEquals(
            TelemetryErrorType.TCP_RESET to TelemetryPhase.CONNECTION,
            reset.type to reset.phase
        )
        assertEquals(
            TelemetryErrorType.HTTP_RESPONSE_INVALID_CONTENT_LENGTH_MISMATCH to
                TelemetryPhase.APPLICATION,
            closed.type to closed.phase
        )
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

        assertEquals(TelemetryStep.TLS, event.step)
        assertEquals(TelemetryPhase.CONNECTION, event.phase)
        assertEquals(TelemetryErrorType.TLS_CERT_AUTHORITY_INVALID, event.type)
        assertEquals(
            TelemetryAttempt(
                "1.1.1.1",
                TelemetryProxyType.DIRECT,
                40,
                TelemetryAttemptResult.FAILED,
                TelemetryErrorType.TLS_CERT_AUTHORITY_INVALID,
                TelemetryStep.TLS
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
                TelemetryAttempt(
                    "1.1.1.1",
                    TelemetryProxyType.DIRECT,
                    300,
                    TelemetryAttemptResult.CANCELED,
                    null,
                    null
                ),
                TelemetryAttempt(
                    "1.1.1.2",
                    TelemetryProxyType.DIRECT,
                    null,
                    TelemetryAttemptResult.CANCELED,
                    null,
                    null
                )
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
        assertEquals(TelemetryErrorType.DNS_NAME_NOT_RESOLVED, event.type)
        assertEquals(TelemetryPhase.DNS, event.phase)
    }

    private companion object {
        val TLS = ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA")
    }
}
