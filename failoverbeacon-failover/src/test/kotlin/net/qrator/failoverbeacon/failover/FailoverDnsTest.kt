package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.ProbeResult
import net.qrator.failoverbeacon.failover.repository.AddressRepository
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher

class FailoverDnsTest {
    private val log = LogRecorder()
    private var now = 0L
    private val resolver = FakeDns()
    private val executor = ManualExecutor()
    private lateinit var repository: AddressRepository

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `fresh addresses come with the additional ones and are refreshed in the background`() {
        val dns = dns(resolved = listOf(ip(1)), additional = listOf(ip(7)))
        resolver.answer(API, ip(2))

        val first = dns.lookup(API)
        executor.runAll()
        val second = dns.lookup(API)

        assertEquals(listOf(ip(1), ip(7)), first)
        assertEquals(listOf(ip(2), ip(7)), second)
    }

    @Test
    fun `without fresh addresses the host is resolved on the calling thread`() {
        val dns = dns(resolved = listOf(ip(1)), additional = listOf(ip(7)))
        resolver.answer(API, ip(2))
        now = MAX_AGE + 1

        assertEquals(listOf(ip(2), ip(7)), dns.lookup(API))
        assertEquals(0, executor.queued)
    }

    @Test
    fun `a failed resolution gives the last and the additional addresses`() {
        val dns = dns(resolved = listOf(ip(1)), additional = listOf(ip(7)))
        resolver.fail(API)
        now = MAX_AGE + 1

        assertEquals(listOf(ip(1), ip(7)), dns.lookup(API))
    }

    @Test
    fun `a failed resolution without earlier answers gives the additional addresses`() {
        val dns = dns(additional = listOf(ip(7)))
        resolver.fail(API)

        assertEquals(listOf(ip(7)), dns.lookup(API))
    }

    @Test
    fun `a failed resolution with nothing to give gives nothing`() {
        val dns = dns()
        resolver.fail(API)

        assertNull(dns.lookup(API))
    }

    @Test
    fun `DNS addresses come first and duplicates are dropped`() {
        val dns = dns(additional = listOf(ip(7), ip(8)))
        resolver.answer(API, ip(1), ip(7))

        assertEquals(listOf(ip(1), ip(7), ip(8)), dns.lookup(API))
    }

    @Test
    fun `addresses are ordered by the probe results`() {
        val dns = dns(resolved = listOf(ip(1), ip(2)), additional = listOf(ip(7)))
        repository.saveProbe(API, ip(1), ProbeResult.Failed)
        repository.saveProbe(API, ip(7), ProbeResult.Connected(connectNanos = 40))

        assertEquals(listOf(ip(7), ip(2), ip(1)), dns.lookup(API))
    }

    @Test
    fun `hosts outside the config are left to the client's DNS`() {
        val dns = dns(resolved = listOf(ip(1)))

        assertTrue(dns.manages(API))
        assertFalse(dns.manages(OTHER))
        assertNull(dns.lookup(OTHER))
        assertEquals(emptyList(), resolver.lookups)
        assertEquals(0, executor.queued)
    }

    private fun dns(
        resolved: List<InetAddress>? = null,
        additional: List<InetAddress> = emptyList()
    ): FailoverDns {
        val host = FailoverHostConfig(API, 443, additional, probeConnections = true)
        repository = AddressRepository(listOf(host), clock = { now })
        resolved?.let { repository.saveResolved(API, it) }
        val refresher = AddressRefresher(resolver::lookup, repository, executor)
        return FailoverDns(repository, refresher, clock = { now }, maxAgeNanos = MAX_AGE)
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
        const val MAX_AGE = 100L
    }
}
