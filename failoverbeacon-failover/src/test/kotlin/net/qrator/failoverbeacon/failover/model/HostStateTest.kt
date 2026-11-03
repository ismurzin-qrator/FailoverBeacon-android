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
    fun `resolved addresses come before the additional ones without duplicates`() {
        assertEquals(listOf(ip(7)), state.ips)
        assertEquals(listOf(ip(1), ip(7), IPV6), state.answered(ip(1), ip(7), IPV6).ips)
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
    fun `results change the order and those of unknown addresses are dropped`() {
        val resolved = state.answered(ip(1))

        assertEquals(listOf(ip(7), ip(1)), resolved.withProbe(ip(7), connected).ips)
        assertEquals(resolved.addresses, resolved.withProbe(ip(2), connected).addresses)
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
            .withProbe(ip(1), connected)

        val cleared = failed.withConnectOutcome(IP_1, ConnectOutcome.CONNECTED_SECURE)

        assertEquals(listOf(ip(7), ip(1)), failed.ips)
        assertEquals(listOf(ip(1), ip(7)), cleared.ips)
    }

    @Test
    fun `a probe round is stale after the interval and answers and probes keep its time`() {
        val started = state.answered(ip(1)).withProbeRound(atNanos = 10)
        val updated = started.answered(ip(1), ip(2)).withProbe(ip(2), LayerResult.Failed)
        now = 9 + INTERVAL
        val beforeInterval = updated.isProbeStale(timer)
        now = 10 + INTERVAL

        assertTrue(state.isProbeStale(timer))
        assertFalse(beforeInterval)
        assertTrue(updated.isProbeStale(timer))
    }

    private fun HostState.answered(vararg addresses: InetAddress): HostState =
        withDns(dns.withAnswer(addresses.toList(), atNanos = 0))

    private companion object {
        const val INTERVAL = 50L
        const val IP_1 = "192.0.2.1"
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
    }
}
