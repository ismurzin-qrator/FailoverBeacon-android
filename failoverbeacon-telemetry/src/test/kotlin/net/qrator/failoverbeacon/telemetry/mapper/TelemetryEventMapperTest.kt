package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeConnection
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.core.call.model.FailureType.ABORTED
import net.qrator.failoverbeacon.core.call.model.FailureType.ADDRESS_INVALID
import net.qrator.failoverbeacon.core.call.model.FailureType.ADDRESS_UNREACHABLE
import net.qrator.failoverbeacon.core.call.model.FailureType.CERT_NAME_MISMATCH
import net.qrator.failoverbeacon.core.call.model.FailureType.CERT_PIN_MISMATCH
import net.qrator.failoverbeacon.core.call.model.FailureType.CERT_UNTRUSTED
import net.qrator.failoverbeacon.core.call.model.FailureType.CLOSED
import net.qrator.failoverbeacon.core.call.model.FailureType.NAME_NOT_RESOLVED
import net.qrator.failoverbeacon.core.call.model.FailureType.OTHER
import net.qrator.failoverbeacon.core.call.model.FailureType.PROTOCOL_ERROR
import net.qrator.failoverbeacon.core.call.model.FailureType.REDIRECT_LOOP
import net.qrator.failoverbeacon.core.call.model.FailureType.REFUSED
import net.qrator.failoverbeacon.core.call.model.FailureType.RESET
import net.qrator.failoverbeacon.core.call.model.FailureType.STREAM_RESET
import net.qrator.failoverbeacon.core.call.model.FailureType.TIMED_OUT
import net.qrator.failoverbeacon.core.call.model.FailureType.TLS_PROTOCOL_ERROR
import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.core.network.model.NetworkTransport
import net.qrator.failoverbeacon.telemetry.MS
import net.qrator.failoverbeacon.telemetry.attempt
import net.qrator.failoverbeacon.telemetry.exchange
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttempt
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttemptResult
import net.qrator.failoverbeacon.telemetry.model.TelemetryErrorType
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent
import net.qrator.failoverbeacon.telemetry.model.TelemetryNetwork
import net.qrator.failoverbeacon.telemetry.model.TelemetryNetworkTransport
import net.qrator.failoverbeacon.telemetry.model.TelemetryPhase
import net.qrator.failoverbeacon.telemetry.model.TelemetryProxyType
import net.qrator.failoverbeacon.telemetry.model.TelemetryStep
import net.qrator.failoverbeacon.telemetry.model.TelemetryTimings
import net.qrator.failoverbeacon.telemetry.model.TelemetryTls

class TelemetryEventMapperTest {
    private val mapper = TelemetryEventMapper()

    @Test
    fun `maps every field of an exchange, durations in whole milliseconds`() {
        val untrusted = ConnectResult.Failed(ExchangeStep.TLS, CERT_UNTRUSTED)
        val exchange = exchange(
            request = ExchangeRequest("https", "a.com", 443, "POST", bodyBytes = 3),
            response = ExchangeResponse(200, contentLength = 5, bodyBytes = 5),
            connection = ExchangeConnection("1.1.1.1", "h2", ProxyType.HTTP, HANDSHAKE),
            reusedConnection = true,
            dnsAddresses = listOf("1.1.1.1", "1.1.1.2"),
            attempts = listOf(
                attempt("1.1.1.1", ConnectResult.Connected, 20 * MS, ProxyType.HTTP),
                attempt("1.1.1.2", ConnectResult.Superseded, connectNanos = null, ProxyType.SOCKS),
                attempt("1.1.1.3", untrusted, 40 * MS + 999_999)
            ),
            timings = ExchangeTimings(MS + 999_999, 20 * MS, 12 * MS, 2 * MS, 30 * MS, 4 * MS),
            network = CONNECTED
        )

        val expected = TelemetryEvent(
            callId = 7,
            resendCount = 1,
            scheme = "https",
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
            reusedConnection = true,
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
                    type = null,
                    step = null
                ),
                TelemetryAttempt(
                    "1.1.1.2",
                    TelemetryProxyType.SOCKS,
                    null,
                    TelemetryAttemptResult.CANCELED,
                    type = null,
                    step = null
                ),
                TelemetryAttempt(
                    "1.1.1.3",
                    TelemetryProxyType.DIRECT,
                    40,
                    TelemetryAttemptResult.FAILED,
                    TelemetryErrorType.TLS_CERT_AUTHORITY_INVALID,
                    TelemetryStep.TLS
                )
            ),
            network = TelemetryNetwork.Connected(
                transport = TelemetryNetworkTransport.CELLULAR,
                vpn = true,
                validated = true,
                captivePortal = false,
                metered = true,
                roaming = false,
                downstreamKbps = 30_000,
                upstreamKbps = 10_000,
                signalStrength = -90
            )
        )
        assertEquals(expected, mapper.toEvent(exchange))
    }

    @Test
    fun `keeps an unknown network apart from no network`() {
        assertEquals(
            TelemetryNetwork.Unknown,
            mapper.toEvent(exchange(network = NetworkSnapshot.Unknown)).network
        )
        assertEquals(
            TelemetryNetwork.None,
            mapper.toEvent(exchange(network = NetworkSnapshot.None)).network
        )
    }

    @Test
    fun `names each network transport as core does`() {
        for (transport in NetworkTransport.entries) {
            val network = mapper.toEvent(exchange(network = CONNECTED.copy(transport = transport)))
                .network

            assertEquals(
                transport.name,
                assertIs<TelemetryNetwork.Connected>(network).transport.name
            )
        }
    }

    // Codes of the NEL dictionary (§6); the codes of a response cut short and of a redirect loop
    // are Chromium's (network_error_logging_service.cc).
    @Test
    fun `names each outcome by its NEL type`() {
        val types = listOf(
            ok(status = 399) to "ok",
            ok(status = 400) to "http.error",
            canceled(TLS) to "abandoned",
            failed(DNS, NAME_NOT_RESOLVED) to "dns.name_not_resolved",
            failed(DNS, TIMED_OUT) to "dns.timed_out",
            failed(DNS, null) to "dns.failed",
            failed(TCP, REFUSED) to "tcp.refused",
            failed(TCP, RESET) to "tcp.reset",
            failed(TCP, ABORTED) to "tcp.aborted",
            failed(TCP, ADDRESS_INVALID) to "tcp.address_invalid",
            failed(TCP, ADDRESS_UNREACHABLE) to "tcp.address_unreachable",
            failed(TCP, CLOSED) to "tcp.closed",
            failed(TCP, null) to "tcp.failed",
            failed(TLS, TIMED_OUT) to "tcp.timed_out",
            failed(TLS, CERT_UNTRUSTED) to "tls.cert.authority_invalid",
            failed(TLS, CERT_NAME_MISMATCH) to "tls.cert.name_invalid",
            failed(TLS, CERT_PIN_MISMATCH) to "tls.cert.pinned_key_not_in_cert_chain",
            failed(TLS, TLS_PROTOCOL_ERROR) to "tls.protocol.error",
            failed(TLS, OTHER) to "tls.failed",
            failed(REQUEST, PROTOCOL_ERROR) to "http.protocol.error",
            failed(REQUEST, null) to "http.failed",
            failed(RESPONSE, CLOSED, response = null) to "http.response.invalid.empty",
            failed(RESPONSE, CLOSED, CUT_BODY) to "http.response.invalid.content_length_mismatch",
            failed(RESPONSE, CLOSED) to "http.response.invalid.incomplete_chunked_encoding",
            failed(RESPONSE, PROTOCOL_ERROR) to "http.response.invalid",
            failed(RESPONSE, REDIRECT_LOOP) to "http.response.redirect_loop",
            failed(RESPONSE, STREAM_RESET) to "http.protocol.error",
            failed(RESPONSE, null) to "http.failed",
            failed(null, null) to "unknown"
        )

        for ((exchange, code) in types) {
            assertEquals(code, mapper.toEvent(exchange).type.code, "${exchange.outcome}")
        }
    }

    @Test
    fun `keeps the step a failure or a cancel happened at, the phase is its type's`() {
        val reset = mapper.toEvent(failed(RESPONSE, RESET, CUT_BODY))
        val canceled = mapper.toEvent(canceled(TLS))

        assertEquals(TelemetryStep.RESPONSE to TelemetryPhase.CONNECTION, reset.step to reset.phase)
        assertEquals(
            TelemetryStep.TLS to TelemetryPhase.APPLICATION,
            canceled.step to canceled.phase
        )
        assertEquals(null, mapper.toEvent(ok(status = 200)).step)
    }

    private fun ok(status: Int) = exchange(response = ExchangeResponse(status, null, bodyBytes = 0))

    private fun canceled(step: ExchangeStep) = exchange(outcome = ExchangeOutcome.Canceled(step))

    private fun failed(
        step: ExchangeStep?,
        type: FailureType?,
        response: ExchangeResponse? = ExchangeResponse(200, contentLength = null, bodyBytes = null)
    ) = exchange(response = response, outcome = ExchangeOutcome.Failed(step, type))

    private companion object {
        val CONNECTED = NetworkSnapshot.Connected(
            transport = NetworkTransport.CELLULAR,
            vpn = true,
            validated = true,
            captivePortal = false,
            metered = true,
            roaming = false,
            downstreamKbps = 30_000,
            upstreamKbps = 10_000,
            signalStrength = -90
        )
        val HANDSHAKE = ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA")
        val CUT_BODY = ExchangeResponse(200, contentLength = 100, bodyBytes = null)
        val DNS = ExchangeStep.DNS
        val TCP = ExchangeStep.TCP
        val TLS = ExchangeStep.TLS
        val REQUEST = ExchangeStep.REQUEST
        val RESPONSE = ExchangeStep.RESPONSE
    }
}
