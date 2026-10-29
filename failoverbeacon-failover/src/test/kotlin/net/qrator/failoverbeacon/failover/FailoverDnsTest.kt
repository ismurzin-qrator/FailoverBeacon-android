package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.cache.AddressCache
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher

class FailoverDnsTest {
    private val log = LogRecorder()
    private var now = 0L
    private val cache = AddressCache({ now }, MAX_AGE)
    private val system = FakeDns()
    private val executor = ManualExecutor()

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `prepared addresses come with the additional ones and are refreshed in the background`() {
        val dns = dns(prepared = listOf(ip(1)), additional = listOf(ip(7)))
        system.answer(API, ip(2))

        val first = dns.preparedAddresses(API)
        executor.runAll()
        val second = dns.preparedAddresses(API)

        assertEquals(listOf(ip(1), ip(7)), first)
        assertEquals(listOf(ip(2), ip(7)), second)
    }

    @Test
    fun `prepared addresses older than the limit are not given`() {
        val dns = dns(prepared = listOf(ip(1)))
        now = MAX_AGE + 1

        assertNull(dns.preparedAddresses(API))
        assertEquals(0, executor.queued)
    }

    @Test
    fun `a resolved answer gets the additional addresses and is prepared for the next lookup`() {
        val dns = dns(additional = listOf(ip(7)))

        val resolved = dns.onResolved(API, listOf(ip(2)))

        assertEquals(listOf(ip(2), ip(7)), resolved)
        assertEquals(listOf(ip(2), ip(7)), dns.preparedAddresses(API))
    }

    @Test
    fun `an empty answer gets the additional addresses and keeps the prepared ones`() {
        val dns = dns(prepared = listOf(ip(1)), additional = listOf(ip(7)))

        assertEquals(listOf(ip(7)), dns.onResolved(API, emptyList()))
        assertEquals(listOf(ip(1), ip(7)), dns.preparedAddresses(API))
    }

    @Test
    fun `a failed lookup gets the last prepared and the additional addresses`() {
        val dns = dns(prepared = listOf(ip(1)), additional = listOf(ip(7)))
        now = MAX_AGE + 1

        assertEquals(listOf(ip(1), ip(7)), dns.onResolveFailed(API))
    }

    @Test
    fun `a failed lookup without prepared addresses gets the additional ones`() {
        val dns = dns(additional = listOf(ip(7)))

        assertEquals(listOf(ip(7)), dns.onResolveFailed(API))
    }

    @Test
    fun `a failed lookup with nothing to give gets nothing`() {
        val dns = dns()

        assertNull(dns.onResolveFailed(API))
    }

    @Test
    fun `DNS addresses come first and duplicates are dropped`() {
        val dns = dns(additional = listOf(ip(7), ip(8)))

        assertEquals(listOf(ip(1), ip(7), ip(8)), dns.onResolved(API, listOf(ip(1), ip(7))))
    }

    @Test
    fun `hosts outside the config are left to the client's DNS`() {
        val dns = dns(prepared = listOf(ip(1)))

        assertTrue(dns.manages(API))
        assertFalse(dns.manages(OTHER))
        assertNull(dns.preparedAddresses(OTHER))
        assertEquals(listOf(ip(2)), dns.onResolved(OTHER, listOf(ip(2))))
        assertNull(dns.onResolveFailed(OTHER))
        assertNull(cache.lastAddresses(OTHER))
        assertEquals(0, executor.queued)
    }

    private fun dns(
        prepared: List<InetAddress>? = null,
        additional: List<InetAddress> = emptyList()
    ): FailoverDns {
        prepared?.let { cache.put(API, it) }
        val refresher = AddressRefresher(system::lookup, cache, executor)
        return FailoverDns(listOf(FailoverHostConfig(API, additional)), cache, refresher)
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
        const val MAX_AGE = 100L
    }
}
