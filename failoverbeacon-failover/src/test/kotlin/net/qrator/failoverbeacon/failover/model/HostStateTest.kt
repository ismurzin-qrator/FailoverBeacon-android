package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.tcpResults

class HostStateTest {
    private var now = 0L
    private val timer = RefreshTimer({ now }, INTERVAL)
    private val state = HostState(hostConfig("api.example", additional = listOf(ip(7))))
    private val connected = LayerResult.Passed(nanos = 40)

    @Test
    fun `a new state has the additional addresses unprobed`() {
        assertEquals(mapOf(ip(7) to LayerResult.Unknown), state.tcpResults)
        assertFalse(state.dns.hasAnswer)
    }

    @Test
    fun `resolved addresses come before the additional ones without duplicates`() {
        assertEquals(listOf(ip(1), ip(7), IPV6), state.answered(ip(1), ip(7), IPV6).ips)
    }

    @Test
    fun `a new answer replaces the resolved addresses`() {
        assertEquals(listOf(ip(2), ip(7)), state.answered(ip(1)).answered(ip(2)).ips)
    }

    @Test
    fun `a probe result changes the order`() {
        val probed = state.answered(ip(1)).withProbe(ip(7), connected)

        assertEquals(listOf(ip(7), ip(1)), probed.ips)
    }

    @Test
    fun `a probe result of an address the host no longer has is dropped`() {
        val resolved = state.answered(ip(1))

        assertEquals(resolved.tcpResults, resolved.withProbe(ip(2), LayerResult.Failed).tcpResults)
    }

    @Test
    fun `a new answer keeps the results of the addresses it still has`() {
        val resolved = state
            .answered(ip(1), ip(2))
            .withProbe(ip(1), connected)
            .withProbe(ip(2), LayerResult.Failed)
            .withProbe(ip(7), LayerResult.Failed)
            .answered(ip(1), ip(3))

        assertEquals(
            mapOf(ip(1) to connected, ip(3) to LayerResult.Unknown, ip(7) to LayerResult.Failed),
            resolved.tcpResults
        )
    }

    @Test
    fun `a probe does not clear a TLS failure and a connection does`() {
        val failed = state
            .answered(ip(1))
            .withConnectOutcome(IP_1, ConnectOutcome.TLS_FAILED)
            .withProbe(ip(1), connected)

        val cleared = failed.withConnectOutcome(IP_1, ConnectOutcome.CONNECTED_SECURE)

        assertEquals(listOf(ip(7), ip(1)), failed.ips)
        assertEquals(LayerResult.Failed, failed.addresses.last().tls)
        assertEquals(listOf(ip(1), ip(7)), cleared.ips)
    }

    @Test
    fun `a connect to an address the host never had is dropped`() {
        val resolved = state.answered(ip(1))

        val updated = resolved.withConnectOutcome("192.0.2.2", ConnectOutcome.TLS_FAILED)

        assertEquals(resolved.addresses, updated.addresses)
    }

    @Test
    fun `an address DNS no longer returns is forgotten with its results`() {
        val failed = state.answered(ip(1)).withConnectOutcome(IP_1, ConnectOutcome.TLS_FAILED)

        val returned = failed.answered(ip(2)).answered(ip(1))

        assertEquals(HostAddress(ip(1)), returned.addresses.first { it.address == ip(1) })
    }

    @Test
    fun `a failed resolution keeps the addresses and their results`() {
        val probed = state.answered(ip(1)).withProbe(ip(1), connected)

        val failed = probed.withDns(probed.dns.withFailure(atNanos = 10))

        assertEquals(probed.tcpResults, failed.tcpResults)
    }

    @Test
    fun `probes are stale before the first round and after the interval`() {
        val started = state.withProbeRound(atNanos = 10)
        now = 10 + INTERVAL - 1
        val beforeInterval = started.isProbeStale(timer)
        now = 10 + INTERVAL

        assertTrue(state.isProbeStale(timer))
        assertFalse(beforeInterval)
        assertTrue(started.isProbeStale(timer))
    }

    @Test
    fun `a new answer and a probe result keep the round time and the round keeps the results`() {
        val started = state
            .answered(ip(1))
            .withProbe(ip(1), connected)
            .withProbeRound(atNanos = 10)

        val updated = started.answered(ip(1), ip(2)).withProbe(ip(2), LayerResult.Failed)
        now = 10 + INTERVAL - 1

        assertEquals(connected, started.tcpResults[ip(1)])
        assertFalse(updated.isProbeStale(timer))
    }

    private fun HostState.answered(vararg addresses: InetAddress): HostState =
        withDns(dns.withAnswer(addresses.toList(), atNanos = 0))

    private companion object {
        const val INTERVAL = 50L
        const val IP_1 = "192.0.2.1"
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
    }
}
