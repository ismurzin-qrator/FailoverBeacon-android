package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.failover.fakes.ip

class AddressRankingTest {
    private val fast = ProbeResult.Connected(connectNanos = 40)
    private val slow = ProbeResult.Connected(connectNanos = 300)

    @Test
    fun `connected IPv4 addresses come first by time, then unknown ones, then failed ones`() {
        val source = listOf(ip(1), ip(2), ip(3), ip(4), ip(5), ip(7))
        val known = listOf(
            HostAddress(ip(1), ProbeResult.Failed),
            HostAddress(ip(3), slow),
            HostAddress(ip(4), ProbeResult.Failed),
            HostAddress(ip(5), fast)
        )

        val ranked = AddressRanking.rank(source, known).map { it.address }

        assertEquals(listOf(ip(5), ip(3), ip(2), ip(7), ip(1), ip(4)), ranked)
    }

    @Test
    fun `failed addresses keep the source order`() {
        val known = listOf(
            HostAddress(ip(2), ProbeResult.Failed),
            HostAddress(ip(1), ProbeResult.Failed)
        )

        val ranked = AddressRanking.rank(listOf(ip(1), ip(2), ip(7)), known).map { it.address }

        assertEquals(listOf(ip(7), ip(1), ip(2)), ranked)
    }

    @Test
    fun `IPv6 addresses keep their places`() {
        val source = listOf(IPV6, ip(1), OTHER_IPV6, ip(2), ip(7))
        val known = listOf(HostAddress(ip(1), ProbeResult.Failed), HostAddress(ip(2), fast))

        val ranked = AddressRanking.rank(source, known).map { it.address }

        assertEquals(listOf(IPV6, ip(2), OTHER_IPV6, ip(7), ip(1)), ranked)
    }

    @Test
    fun `addresses outside the source are dropped and new ones are unknown`() {
        val known = listOf(HostAddress(ip(1), fast), HostAddress(ip(2), ProbeResult.Failed))

        val ranked = AddressRanking.rank(listOf(ip(1), ip(3)), known)

        assertEquals(
            listOf(HostAddress(ip(1), fast), HostAddress(ip(3), ProbeResult.Unknown)),
            ranked
        )
    }

    private companion object {
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
        val OTHER_IPV6: InetAddress = InetAddress.getByName("2001:db8::2")
    }
}
