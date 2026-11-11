package net.qrator.failoverbeacon.failover.traffic

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeConnection
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.core.network.model.NetworkTransport
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.HostAddress
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.MaxAge
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions

class TrafficFeedbackTest {
    private val sessions = NetworkSessions(
        KnownNetworks(listOf(hostConfig(API, listOf(ip(1), ip(2)))), maxNetworks = 8),
        MaxAge({
            0L
        }, millis = 50)
    ).apply { switchTo(network(1)) }
    private val feedback = TrafficFeedback(sessions)
    private val session get() = assertNotNull(sessions.current)
    private val probed = LayerResult.Passed(nanos = 40)
    private val passed = LayerResult.Passed(nanos = null)

    private fun addressOf(address: InetAddress): HostAddress =
        assertNotNull(session[API]).addresses.first { it.address == address }

    @Test
    fun `a failed connect is saved and moves the address last`() {
        session.saveTcpProbe(API, ip(1), probed)

        feedback.save(exchange(attempt(ip(1), failed(ExchangeStep.TLS))))

        assertEquals(HostAddress(ip(1), probed, LayerResult.Failed), addressOf(ip(1)))
        assertEquals(listOf(ip(2), ip(1)), session[API]?.ips)
    }

    @Test
    fun `the scheme of the request tells a secure connection`() {
        feedback.save(exchange(attempt(ip(1), ConnectResult.Connected)))
        feedback.save(exchange(attempt(ip(2), ConnectResult.Connected), scheme = "http"))

        assertEquals(HostAddress(ip(1), passed, passed), addressOf(ip(1)))
        assertEquals(HostAddress(ip(2), passed), addressOf(ip(2)))
    }

    @Test
    fun `the last connect to an address wins`() {
        feedback.save(
            exchange(
                attempt(ip(1), failed(ExchangeStep.TLS)),
                attempt(ip(1), ConnectResult.Connected)
            )
        )

        assertEquals(HostAddress(ip(1), passed, passed), addressOf(ip(1)))
    }

    @Test
    fun `a lost race, a cancel and a failure without a type write nothing`() {
        feedback.save(
            exchange(
                attempt(ip(1), ConnectResult.Superseded),
                attempt(ip(1), ConnectResult.Canceled),
                attempt(ip(1), ConnectResult.Failed(ExchangeStep.TCP, type = null))
            )
        )

        assertEquals(HostAddress(ip(1)), addressOf(ip(1)))
    }

    @Test
    fun `a connect through a proxy writes nothing`() {
        feedback.save(
            exchange(
                attempt(ip(1), failed(ExchangeStep.TCP), ProxyType.HTTP),
                attempt(ip(2), failed(ExchangeStep.TLS), ProxyType.SOCKS)
            )
        )

        assertEquals(HostAddress(ip(1)), addressOf(ip(1)))
        assertEquals(HostAddress(ip(2)), addressOf(ip(2)))
    }

    @Test
    fun `an exchange of another or an unknown network writes nothing`() {
        feedback.save(
            exchange(attempt(ip(1), failed(ExchangeStep.TCP)), network = NetworkSnapshot.Unknown)
        )
        sessions.switchTo(network(2))
        feedback.save(exchange(attempt(ip(1), failed(ExchangeStep.TCP))))

        assertEquals(HostAddress(ip(1)), addressOf(ip(1)))
    }

    @Test
    fun `an exchange of the same network is written after a reconnect to it`() {
        sessions.switchTo(null)
        sessions.switchTo(network(1))

        feedback.save(exchange(attempt(ip(1), failed(ExchangeStep.TCP))))

        assertEquals(HostAddress(ip(1), LayerResult.Failed), addressOf(ip(1)))
    }

    @Test
    fun `without a network, a server name or a known address nothing is written`() {
        feedback.save(exchange(attempt(ip(3), failed(ExchangeStep.TCP))))
        feedback.save(exchange(attempt(ip(1), failed(ExchangeStep.TCP)), host = null))
        feedback.save(exchange(attempt(ip(1), failed(ExchangeStep.TCP)), host = "other.example"))
        val before = assertNotNull(session[API]).addresses
        sessions.switchTo(null)

        feedback.save(exchange(attempt(ip(1), failed(ExchangeStep.TCP))))

        sessions.switchTo(network(1))
        assertEquals(before, session[API]?.addresses)
        assertEquals(listOf(HostAddress(ip(1)), HostAddress(ip(2))), before)
    }

    @Test
    fun `a request without a response moves the address last, any response brings it ahead`() {
        feedback.save(exchange(connection = direct(ip(1)), outcome = silent(FailureType.TIMED_OUT)))
        val afterTimeout = addressOf(ip(1))
        val orderAfterTimeout = session[API]?.ips

        feedback.save(exchange(connection = direct(ip(1)), response = response(503)))

        assertEquals(
            HostAddress(ip(1), body = LayerResult.Failed, bodyProbedAtMillis = 0),
            afterTimeout
        )
        assertEquals(listOf(ip(2), ip(1)), orderAfterTimeout)
        assertEquals(HostAddress(ip(1), body = passed, bodyProbedAtMillis = 0), addressOf(ip(1)))
        assertEquals(listOf(ip(1), ip(2)), session[API]?.ips)
    }

    @Test
    fun `a reset, a close and an abort while sending or waiting for a response count as silence`() {
        val results = listOf(
            silent(FailureType.RESET, ExchangeStep.REQUEST),
            silent(FailureType.CLOSED),
            silent(FailureType.ABORTED)
        ).map { outcome ->
            feedback.save(exchange(connection = direct(ip(1)), response = response(200)))
            feedback.save(exchange(connection = direct(ip(1)), outcome = outcome))
            addressOf(ip(1)).body
        }

        assertEquals(List(3) { LayerResult.Failed }, results)
    }

    @Test
    fun `a cancel, a cut response, a server reset and a proxy leave the address as it was`() {
        feedback.save(
            exchange(
                connection = direct(ip(1)),
                outcome = ExchangeOutcome.Canceled(ExchangeStep.RESPONSE)
            )
        )
        feedback.save(
            exchange(
                connection = direct(ip(1)),
                response = response(200),
                outcome = silent(FailureType.CLOSED)
            )
        )
        feedback.save(
            exchange(connection = direct(ip(1)), outcome = silent(FailureType.STREAM_RESET))
        )
        feedback.save(
            exchange(
                connection = direct(ip(1)).copy(proxy = ProxyType.HTTP),
                outcome = silent(FailureType.TIMED_OUT)
            )
        )

        assertEquals(HostAddress(ip(1)), addressOf(ip(1)))
    }

    private fun silent(type: FailureType, step: ExchangeStep = ExchangeStep.RESPONSE) =
        ExchangeOutcome.Failed(step, type)

    private fun direct(address: InetAddress) =
        ExchangeConnection(assertNotNull(address.hostAddress), "h2", ProxyType.DIRECT, tls = null)

    private fun response(statusCode: Int) = ExchangeResponse(statusCode, null, bodyBytes = 0)

    private fun failed(step: ExchangeStep) = ConnectResult.Failed(step, FailureType.TIMED_OUT)

    private fun attempt(
        address: InetAddress,
        result: ConnectResult,
        proxy: ProxyType = ProxyType.DIRECT
    ) = ConnectAttempt(assertNotNull(address.hostAddress), proxy, connectNanos = 1, result)

    private fun exchange(
        vararg attempts: ConnectAttempt,
        host: String? = API,
        scheme: String = "https",
        network: NetworkSnapshot = CONNECTED,
        connection: ExchangeConnection? = null,
        response: ExchangeResponse? = null,
        outcome: ExchangeOutcome = ExchangeOutcome.Ok
    ) = Exchange(
        callId = 1,
        request = ExchangeRequest(scheme, host, 443, "GET", bodyBytes = null),
        response = response,
        resendCount = 0,
        connection = connection,
        reusedConnection = false,
        dnsAddresses = null,
        attempts = attempts.toList(),
        timings = ExchangeTimings(null, null, null, null, null, null),
        startTimeMillis = 0,
        durationNanos = 0,
        outcome = outcome,
        network = network,
        foreground = null
    )

    private companion object {
        const val API = "api.example"
        val CONNECTED = NetworkSnapshot.Connected(
            handle = 1,
            transport = NetworkTransport.WIFI,
            vpn = false,
            validated = true,
            captivePortal = false,
            metered = false,
            roaming = null,
            downstreamKbps = null,
            upstreamKbps = null,
            signalStrength = null,
            carrier = null,
            blocked = false
        )
    }
}
