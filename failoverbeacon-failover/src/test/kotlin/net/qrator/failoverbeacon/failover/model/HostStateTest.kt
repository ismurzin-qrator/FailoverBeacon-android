package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.tcpResults

class HostStateTest {
    private var now = 0L
    private val maxAge = MaxAge({ now }, INTERVAL)
    private val state = HostState(hostConfig("api.example", additional = listOf(ip(7))))
    private val connected = LayerResult.Passed(nanos = 40)

    @Test
    fun `resolved addresses come before the additional ones without duplicates`() {
        assertEquals(listOf(ip(7)), state.ips)
        assertEquals(listOf(ip(1), ip(7), IPV6), state.answered(ip(1), ip(7), IPV6).ips)
    }

    @Test
    fun `a new answer keeps the results of the addresses it still has`() {
        val resolved = state
            .answered(ip(1), ip(2))
            .withTcpProbe(ip(1), connected)
            .withTcpProbe(ip(2), LayerResult.Failed)
            .withTcpProbe(ip(7), LayerResult.Failed)
            .answered(ip(1), ip(3))

        assertEquals(
            mapOf(ip(1) to connected, ip(3) to LayerResult.Unknown, ip(7) to LayerResult.Failed),
            resolved.tcpResults
        )
    }

    @Test
    fun `an address DNS no longer returns is forgotten with its results`() {
        val failed = state.answered(ip(1)).withConnectOutcome(IP_1, ConnectOutcome.TLS_FAILED)

        val returned = failed.answered(ip(2)).answered(ip(1))

        assertEquals(HostAddress(ip(1)), returned.addresses.first { it.address == ip(1) })
    }

    @Test
    fun `a failed resolution keeps the addresses and their results`() {
        val probed = state.answered(ip(1)).withTcpProbe(ip(1), connected)

        val failed = probed.withDns(probed.dns.withFailure(atMillis = 10))

        assertEquals(probed.tcpResults, failed.tcpResults)
    }

    @Test
    fun `results change the order and those of unknown addresses are dropped`() {
        val resolved = state.answered(ip(1))

        assertEquals(listOf(ip(7), ip(1)), resolved.withTcpProbe(ip(7), connected).ips)
        assertEquals(resolved.addresses, resolved.withTcpProbe(ip(2), connected).addresses)
        assertEquals(
            resolved.addresses,
            resolved.withConnectOutcome("192.0.2.2", ConnectOutcome.TLS_FAILED).addresses
        )
    }

    @Test
    fun `a probe does not clear a TLS failure and a connection does`() {
        val failed = state
            .answered(ip(1))
            .withConnectOutcome(IP_1, ConnectOutcome.TLS_FAILED)
            .withTcpProbe(ip(1), connected)

        val cleared = failed.withConnectOutcome(IP_1, ConnectOutcome.CONNECTED_SECURE)

        assertEquals(listOf(ip(7), ip(1)), failed.ips)
        assertEquals(listOf(ip(1), ip(7)), cleared.ips)
    }

    @Test
    fun `a probe round is stale after the interval and answers and probes keep its time`() {
        val started = state.answered(ip(1)).withTcpProbeRound(atMillis = 10)
        val updated = started.answered(ip(1), ip(2)).withTcpProbe(ip(2), LayerResult.Failed)
        now = 9 + INTERVAL
        val beforeInterval = updated.isTcpProbeStale(maxAge)
        now = 10 + INTERVAL

        assertTrue(state.isTcpProbeStale(maxAge))
        assertFalse(beforeInterval)
        assertTrue(updated.isTcpProbeStale(maxAge))
    }

    @Test
    fun `the leader is due for a body probe once every IPv4 address has a TCP result`() {
        val answered = state.answered(ip(1))

        val probing = answered.withTcpProbe(ip(1), connected)
        val probed = probing.withTcpProbe(ip(7), LayerResult.Passed(nanos = 90))

        assertNull(probing.addressToBodyProbe(maxAge))
        assertEquals(ip(1), probed.addressToBodyProbe(maxAge))
    }

    @Test
    fun `a single IPv4 address or a leader failed on TCP is not probed`() {
        val single = HostState(hostConfig("api.example", additional = listOf(ip(7))))
            .withTcpProbe(ip(7), connected)
        val allFailed = state.answered(ip(1))
            .withTcpProbe(ip(1), LayerResult.Failed)
            .withTcpProbe(ip(7), LayerResult.Failed)

        assertNull(single.addressToBodyProbe(maxAge))
        assertNull(allFailed.addressToBodyProbe(maxAge))
    }

    @Test
    fun `a passed leader is probed again only after the interval`() {
        val probed = probedState(ip(1), ip(2))
            .withBodyProbeOutcome(ip(1), BodyProbeOutcome.PASSED, atMillis = 10)

        now = 9 + INTERVAL
        assertNull(probed.addressToBodyProbe(maxAge))
        now = 10 + INTERVAL
        assertEquals(ip(1), probed.addressToBodyProbe(maxAge))
    }

    @Test
    fun `a failed leader gives way to the next until the limit of fresh body probes`() {
        val first = probedState(ip(1), ip(2), ip(3), ip(4))
            .withBodyProbeOutcome(ip(1), BodyProbeOutcome.BODY_FAILED, atMillis = 0)
        val second = first.withBodyProbeOutcome(ip(2), BodyProbeOutcome.TLS_FAILED, atMillis = 0)
        val third = second.withBodyProbeOutcome(ip(3), BodyProbeOutcome.BODY_FAILED, atMillis = 0)

        assertEquals(ip(2), first.addressToBodyProbe(maxAge))
        assertEquals(ip(3), second.addressToBodyProbe(maxAge))
        assertEquals(ip(4), third.ips.first())
        assertNull(third.addressToBodyProbe(maxAge))
    }

    private fun probedState(vararg addresses: InetAddress): HostState {
        var probed = HostState(hostConfig("api.example")).answered(*addresses)
        addresses.forEachIndexed { index, address ->
            probed = probed.withTcpProbe(address, LayerResult.Passed(nanos = 10L + index))
        }
        return probed
    }

    private fun HostState.answered(vararg addresses: InetAddress): HostState =
        withDns(dns.withAnswer(addresses.toList(), atMillis = 0))

    private companion object {
        const val INTERVAL = 50L
        const val IP_1 = "192.0.2.1"
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
    }
}
