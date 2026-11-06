package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.FakeTcpProbe
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.idleBodyProber
import net.qrator.failoverbeacon.failover.fakes.idleTcpProber
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.TcpProber
import net.qrator.failoverbeacon.failover.resolve.AddressResolver

class FailoverDnsTest {
    private var now = 0L
    private val resolver = FakeDns()
    private val executor = ManualExecutor()
    private val probes = ManualExecutor()
    private lateinit var sessions: NetworkSessions

    private val session get() = assertNotNull(sessions.current)

    @Test
    fun `fresh addresses come at once and are refreshed in the background`() {
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
        val pool = SdkExecutor("test", 1, 1, 1_000, LinkedBlockingQueue())
        val dns = dns(resolved = listOf(ip(1)), executor = pool, waitMillis = 5_000)
        resolver.answer(API) {
            Thread.sleep(50)
            listOf(ip(2))
        }
        now = INTERVAL

        assertEquals(listOf(ip(2)), dns.lookup(API))
        pool.shutdownNow()
    }

    @Test
    fun `a failed resolution gives what is left and without addresses gives nothing`() {
        resolver.fail(API)
        val answered =
            dns(resolved = listOf(ip(1)), additional = listOf(ip(7)), executor = Runnable::run)
        now = INTERVAL
        val afterAnswer = answered.lookup(API)
        now = 0

        assertEquals(listOf(ip(1), ip(7)), afterAnswer)
        assertEquals(
            listOf(ip(7)),
            dns(additional = listOf(ip(7)), executor = Runnable::run).lookup(API)
        )
        assertNull(dns(executor = Runnable::run).lookup(API))
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
        val dns = dns(resolved = listOf(ip(1)), waitMillis = WAIT_LIMIT_MILLIS)
        session.saveResolveFailure(API)
        now = INTERVAL
        resolver.answer(API, ip(2))

        val start = Instant.now()
        val whileFailed = dns.lookup(API)
        val elapsed = Duration.between(start, Instant.now())
        executor.runAll()

        assertTrue(elapsed < Duration.ofMillis(WAIT_LIMIT_MILLIS), "$elapsed")
        assertEquals(listOf(ip(1)), whileFailed)
        assertEquals(listOf(ip(2)), dns.lookup(API))
    }

    @Test
    fun `hosts outside the config or without a network are left to the client's DNS`() {
        val dns = dns(resolved = listOf(ip(1)), additional = listOf(ip(7)))

        assertTrue(dns.manages(API))
        assertFalse(dns.manages(OTHER))
        assertNull(dns.lookup(OTHER))
        sessions.switchTo(null)
        assertFalse(dns.manages(API))
        assertNull(dns.lookup(API))
        assertEquals(0, probes.queued + executor.queued)
        assertEquals(emptyList(), resolver.lookups)
    }

    private fun dns(
        resolved: List<InetAddress>? = null,
        additional: List<InetAddress> = emptyList(),
        executor: Executor = this.executor,
        waitMillis: Long = WAIT_MILLIS
    ): FailoverDns {
        val hosts = listOf(hostConfig(API, additional))
        val timer = RefreshTimer({ now }, INTERVAL)
        sessions = NetworkSessions(KnownNetworks(hosts, maxNetworks = 8), timer)
        sessions.switchTo(network(1))
        resolved?.let { session.saveAnswer(API, it) }
        val tcpProber = TcpProber(sessions, FakeTcpProbe()::run, probes, idleBodyProber(), true)
        val addressResolver = AddressResolver(
            sessions,
            resolver::lookup,
            executor,
            idleTcpProber(),
            timer,
            true,
            waitMillis
        )
        return FailoverDns(
            sessions,
            tcpProber,
            addressResolver,
            timer,
            isFallbackWithoutEchAllowed = true
        )
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
        const val INTERVAL = 100L
        const val WAIT_MILLIS = 20L
        const val WAIT_LIMIT_MILLIS = 5_000L
    }
}
