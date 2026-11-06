package net.qrator.failoverbeacon.failover.traffic

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
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
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions

class TrafficFeedbackTest {
    private val sessions = NetworkSessions(
        KnownNetworks(listOf(hostConfig(API, listOf(ip(1), ip(2)))), maxNetworks = 8),
        RefreshTimer({
            0L
        }, intervalMillis = 50)
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
        network: NetworkSnapshot = CONNECTED
    ) = Exchange(
        callId = 1,
        request = ExchangeRequest(scheme, host, 443, "GET", bodyBytes = null),
        response = null,
        resendCount = 0,
        connection = null,
        reusedConnection = false,
        dnsAddresses = null,
        attempts = attempts.toList(),
        timings = ExchangeTimings(null, null, null, null, null, null),
        startTimeMillis = 0,
        durationNanos = 0,
        outcome = ExchangeOutcome.Ok,
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
