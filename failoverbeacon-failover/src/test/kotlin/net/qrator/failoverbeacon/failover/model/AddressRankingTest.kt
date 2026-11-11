package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.failover.fakes.ip

class AddressRankingTest {
    private val fast = LayerResult.Passed(nanos = 40)
    private val slow = LayerResult.Passed(nanos = 300)

    @Test
    fun `passed IPv4 addresses come first by time, then unknown ones, then failed ones`() {
        val addresses = listOf(
            HostAddress(ip(1), LayerResult.Failed),
            HostAddress(ip(2)),
            HostAddress(ip(3), slow),
            HostAddress(ip(4), LayerResult.Failed),
            HostAddress(ip(5), fast),
            HostAddress(ip(7))
        )

        assertEquals(listOf(ip(5), ip(3), ip(2), ip(7), ip(1), ip(4)), ranked(addresses))
    }

    @Test
    fun `an address failed on TLS or the body goes last whatever its probe time`() {
        val addresses = listOf(
            HostAddress(ip(1), fast, LayerResult.Failed),
            HostAddress(ip(2), fast, body = LayerResult.Failed),
            HostAddress(ip(3), slow),
            HostAddress(ip(4))
        )

        assertEquals(listOf(ip(3), ip(4), ip(1), ip(2)), ranked(addresses))
    }

    @Test
    fun `an address passed without a time comes after timed ones and before unknown ones`() {
        val addresses = listOf(
            HostAddress(ip(1)),
            HostAddress(ip(2), LayerResult.Passed(nanos = null)),
            HostAddress(ip(3), slow)
        )

        assertEquals(listOf(ip(3), ip(2), ip(1)), ranked(addresses))
    }

    @Test
    fun `when every address failed the probe order stays`() {
        val addresses = listOf(
            HostAddress(ip(1), slow, LayerResult.Failed),
            HostAddress(ip(2), LayerResult.Failed),
            HostAddress(ip(3), fast, LayerResult.Failed)
        )

        assertEquals(listOf(ip(3), ip(1), ip(2)), ranked(addresses))
    }

    @Test
    fun `IPv6 addresses keep their places`() {
        val addresses = listOf(
            HostAddress(IPV6),
            HostAddress(ip(1), LayerResult.Failed),
            HostAddress(OTHER_IPV6),
            HostAddress(ip(2), fast),
            HostAddress(ip(7))
        )

        assertEquals(listOf(IPV6, ip(2), OTHER_IPV6, ip(7), ip(1)), ranked(addresses))
    }

    @Test
    fun `an IPv6 address with a failed connection goes after every IPv4 address`() {
        val addresses = listOf(
            HostAddress(IPV6, LayerResult.Failed),
            HostAddress(ip(1), LayerResult.Failed),
            HostAddress(OTHER_IPV6, LayerResult.Passed(nanos = null), LayerResult.Failed),
            HostAddress(ip(2))
        )

        assertEquals(listOf(ip(2), ip(1), IPV6, OTHER_IPV6), ranked(addresses))
    }

    @Test
    fun `an answered address leads a faster one that was not checked`() {
        val addresses = listOf(
            HostAddress(ip(1), fast),
            HostAddress(ip(2), slow, body = LayerResult.Passed(nanos = null)),
            HostAddress(ip(3), fast, body = LayerResult.Failed)
        )

        assertEquals(listOf(ip(2), ip(1), ip(3)), ranked(addresses))
    }

    private fun ranked(addresses: List<HostAddress>): List<InetAddress> =
        AddressRanking.rank(addresses).map { it.address }

    private companion object {
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
        val OTHER_IPV6: InetAddress = InetAddress.getByName("2001:db8::2")
    }
}
