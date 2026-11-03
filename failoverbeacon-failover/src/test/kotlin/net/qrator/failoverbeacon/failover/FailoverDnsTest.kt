package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.FakeConnect
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.AddressProber
import net.qrator.failoverbeacon.failover.probe.ProbeTasks
import net.qrator.failoverbeacon.failover.resolve.AddressResolver
import net.qrator.failoverbeacon.failover.resolve.ResolveTasks

class FailoverDnsTest {
    private val log = LogRecorder()
    private var now = 0L
    private val resolver = FakeDns()
    private val executor = ManualExecutor()
    private val probes = ManualExecutor()
    private lateinit var sessions: NetworkSessions

    private val session get() = assertNotNull(sessions.current)

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `a lookup of a configured host starts what is stale for every host`() {
        val dns = dns(resolved = listOf(ip(1)), additional = listOf(ip(7)))
        session.startProbeRoundIfStale(API)
        now = INTERVAL

        dns.lookup(API)

        assertEquals(2, probes.queued)
    }

    @Test
    fun `a lookup resolves other hosts that are stale`() {
        val dns = dns(resolved = listOf(ip(1)), otherHosts = listOf(SPARE))

        dns.lookup(API)
        executor.runAll()

        assertEquals(setOf(API, SPARE), resolver.lookups.toSet())
    }

    @Test
    fun `a lookup outside the config or without a network starts nothing`() {
        val dns = dns(resolved = listOf(ip(1)), additional = listOf(ip(7)))

        dns.lookup(OTHER)
        val outsideConfig = probes.queued + executor.queued
        sessions.switchTo(null)
        dns.lookup(API)

        assertEquals(0, outsideConfig)
        assertEquals(0, probes.queued + executor.queued)
    }

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
    fun `a stale answer waits for the new one`() {
        val dns =
            dns(resolved = listOf(ip(1)), additional = listOf(ip(7)), executor = Runnable::run)
        resolver.answer(API, ip(2))
        now = INTERVAL

        assertEquals(listOf(ip(2), ip(7)), dns.lookup(API))
    }

    @Test
    fun `a new answer keeps the results of the addresses that stay`() {
        val dns = dns(resolved = listOf(ip(1), ip(2)), executor = Runnable::run)
        session.saveProbe(API, ip(2), LayerResult.Passed(nanos = 40))
        resolver.answer(API, ip(1), ip(2), ip(3))
        now = INTERVAL

        assertEquals(listOf(ip(2), ip(1), ip(3)), dns.lookup(API))
    }

    @Test
    fun `a failed resolution gives the last and the additional addresses`() {
        val dns =
            dns(resolved = listOf(ip(1)), additional = listOf(ip(7)), executor = Runnable::run)
        resolver.fail(API)
        now = INTERVAL

        assertEquals(listOf(ip(1), ip(7)), dns.lookup(API))
    }

    @Test
    fun `a failed resolution without earlier answers gives the additional addresses`() {
        val dns = dns(additional = listOf(ip(7)), executor = Runnable::run)
        resolver.fail(API)

        assertEquals(listOf(ip(7)), dns.lookup(API))
    }

    @Test
    fun `a failed resolution with nothing to give gives nothing`() {
        val dns = dns(executor = Runnable::run)
        resolver.fail(API)

        assertNull(dns.lookup(API))
    }

    @Test
    fun `a resolver that does not answer ends the wait with the last addresses`() {
        val dns = dns(resolved = listOf(ip(1)))
        now = INTERVAL

        assertEquals(listOf(ip(1)), dns.lookup(API))
        assertEquals(1, executor.queued)
    }

    @Test
    fun `after a failure lookups do not wait until DNS answers`() {
        val dns = dns(resolved = listOf(ip(1)), waitMillis = 5_000)
        session.saveResolveFailure(API)
        now = INTERVAL
        resolver.answer(API, ip(2))

        val start = Instant.now()
        val whileFailed = dns.lookup(API)
        val elapsed = Duration.between(start, Instant.now())
        val queued = executor.queued
        executor.runAll()

        assertTrue(elapsed < Duration.ofSeconds(2), "$elapsed")
        assertEquals(listOf(ip(1)), whileFailed)
        assertEquals(1, queued)
        assertEquals(listOf(ip(2)), dns.lookup(API))
    }

    @Test
    fun `DNS addresses come first and duplicates are dropped`() {
        val dns = dns(additional = listOf(ip(7), ip(8)), executor = Runnable::run)
        resolver.answer(API, ip(1), ip(7))

        assertEquals(listOf(ip(1), ip(7), ip(8)), dns.lookup(API))
    }

    @Test
    fun `addresses are ordered by the probe results`() {
        val dns = dns(resolved = listOf(ip(1), ip(2)), additional = listOf(ip(7)))
        session.saveProbe(API, ip(1), LayerResult.Failed)
        session.saveProbe(API, ip(7), LayerResult.Passed(nanos = 40))

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

    @Test
    fun `without a network configured hosts are left to the client's DNS`() {
        val dns = dns(resolved = listOf(ip(1)))
        sessions.switchTo(null)

        assertFalse(dns.manages(API))
        assertNull(dns.lookup(API))
        assertEquals(emptyList(), resolver.lookups)
    }

    private fun dns(
        resolved: List<InetAddress>? = null,
        additional: List<InetAddress> = emptyList(),
        executor: Executor = this.executor,
        waitMillis: Long = WAIT_MILLIS,
        otherHosts: List<String> = emptyList()
    ): FailoverDns {
        val hosts = listOf(hostConfig(API, additional)) + otherHosts.map { hostConfig(it) }
        val timer = RefreshTimer({ now }, INTERVAL)
        sessions = NetworkSessions(hosts, timer, maxNetworks = 8)
        sessions.switchTo(network(1))
        resolved?.let { session.saveAnswer(API, it) }
        val prober = AddressProber(sessions, ProbeTasks(FakeConnect()::connect, probes), true)
        val tasks = ResolveTasks(resolver::lookup, executor) {}
        val addressResolver = AddressResolver(sessions, tasks, timer, true, waitMillis)
        return FailoverDns(sessions, prober, addressResolver, timer)
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
        const val SPARE = "spare.example"
        const val INTERVAL = 100L
        const val WAIT_MILLIS = 20L
    }
}
