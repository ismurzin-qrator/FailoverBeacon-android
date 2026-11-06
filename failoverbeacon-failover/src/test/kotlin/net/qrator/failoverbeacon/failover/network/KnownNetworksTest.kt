package net.qrator.failoverbeacon.failover.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.model.DnsState
import net.qrator.failoverbeacon.failover.model.HostState

class KnownNetworksTest {
    private val config = hostConfig(API, listOf(ip(7)))
    private val answered =
        HostState(config).withDns(DnsState().withAnswer(listOf(ip(1)), atMillis = 0))
    private val networks = KnownNetworks(listOf(config, hostConfig(OTHER)), maxNetworks = 2)

    @Test
    fun `the least recently used network goes first`() {
        val wifi = networks.of(WIFI)
        networks.of(CELLULAR)
        networks.of(WIFI)

        networks.of("third")

        assertSame(wifi, networks.of(WIFI))
        assertEquals(2, networks.all().size)
    }

    @Test
    fun `saved keys hide the network key`() {
        networks.of(WIFI)

        assertFalse(WIFI in networks.all().keys.single())
    }

    @Test
    fun `an added network is found by its key and keeps every configured host`() {
        val saved = KnownNetworks(listOf(config), maxNetworks = 2)
        saved.of(WIFI)
        val key = saved.all().keys.single()

        networks.putAll(mapOf(key to listOf(answered)))

        assertEquals(listOf(ip(1), ip(7)), networks.of(WIFI)[API]?.ips)
        assertEquals(emptyList(), networks.of(WIFI)[OTHER]?.ips)
    }

    @Test
    fun `clear forgets every network`() {
        networks.of(WIFI).update(API) { answered }

        networks.clear()

        assertTrue(networks.all().isEmpty())
        assertEquals(listOf(ip(7)), networks.of(WIFI)[API]?.ips)
    }

    private companion object {
        const val API = "a.com"
        const val OTHER = "b.com"
        const val WIFI = "WIFI/192.0.2"
        const val CELLULAR = "CELLULAR/250-01"
    }
}
