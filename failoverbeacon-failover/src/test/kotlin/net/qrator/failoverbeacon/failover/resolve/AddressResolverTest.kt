package net.qrator.failoverbeacon.failover.resolve

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions

class AddressResolverTest {
    private var now = 0L
    private val dns = FakeDns()
    private val executor = ManualExecutor()
    private val timer = RefreshTimer({ now }, INTERVAL)
    private val sessions = NetworkSessions(
        KnownNetworks(listOf(hostConfig(API), hostConfig(OTHER)), maxNetworks = 8),
        timer
    ).apply {
        switchTo(network(1))
    }
    private val resolver = newResolver()
    private val onlyAnsweredHosts = newResolver(preresolveOnNetworkChange = false)

    private val session get() = assertNotNull(sessions.current)

    @Test
    fun `stale hosts are resolved in the background once per interval`() {
        dns.answer(API, ip(1))
        dns.answer(OTHER, ip(2))

        resolver.resolveStaleHosts()
        val background = dns.lookups.toList()
        executor.runAll()
        now += INTERVAL - 1
        resolver.resolveStaleHosts()
        val beforeInterval = executor.queued
        now += 1
        resolver.resolveStaleHosts()
        executor.runAll()

        assertEquals(emptyList(), background)
        assertEquals(0, beforeInterval)
        assertEquals(listOf(API, OTHER, API, OTHER), dns.lookups)
    }

    @Test
    fun `a failed host waits for the interval before the next stale resolution`() {
        dns.fail(API)
        dns.fail(OTHER)

        resolver.resolveStaleHosts()
        executor.runAll()
        resolver.resolveStaleHosts()
        val afterFailure = executor.queued
        now += INTERVAL
        resolver.resolveStaleHosts()

        assertEquals(0, afterFailure)
        assertEquals(2, executor.queued)
    }

    @Test
    fun `without a network nothing is resolved`() {
        sessions.switchTo(null)

        resolver.resolveStaleHosts()
        resolver.preresolve()
        resolver.resolveInBackground(API)
        resolver.resolveAndWait(API)

        assertEquals(0, executor.queued)
    }

    @Test
    fun `with preresolve off the first network resolves every host, a later one does not`() {
        onlyAnsweredHosts.preresolve()
        val onEventOfFirstNetwork = executor.queued
        executor.runAll()
        sessions.switchTo(network(2, key = "CELLULAR/001-01"))
        onlyAnsweredHosts.preresolve()
        val onEventOfLaterNetwork = executor.queued

        assertEquals(2, onEventOfFirstNetwork)
        assertEquals(0, onEventOfLaterNetwork)
    }

    @Test
    fun `with preresolve off stale resolution covers the first network and answered hosts`() {
        onlyAnsweredHosts.resolveStaleHosts()
        val onFirstNetwork = executor.queued
        executor.runAll()
        dns.lookups.clear()
        sessions.switchTo(network(2, key = "CELLULAR/001-01"))
        session.saveAnswer(API, listOf(ip(1)))
        now += INTERVAL

        onlyAnsweredHosts.resolveStaleHosts()
        executor.runAll()

        assertEquals(2, onFirstNetwork)
        assertEquals(listOf(API), dns.lookups)
    }

    @Test
    fun `preresolve takes hosts without a fresh answer, also those that just failed`() {
        dns.fail(OTHER)
        session.saveAnswer(API, listOf(ip(1)))

        resolver.preresolve()
        executor.runAll()
        resolver.preresolve()

        assertEquals(listOf(OTHER), dns.lookups)
        assertEquals(1, executor.queued)
    }

    @Test
    fun `waiting callers share the resolution and return after the answer is saved`() {
        val pool = SdkExecutor("test", 1, 1, 1_000, LinkedBlockingQueue())
        val release = CountDownLatch(1)
        dns.answer(API) {
            release.await()
            listOf(ip(1))
        }
        val resolver = newResolver(executor = pool)
        val callers = List(2) { thread { resolver.resolveAndWait(API) } }

        assertTrue(await { dns.lookups.size == 1 })
        release.countDown()
        callers.forEach { it.join(5_000) }

        assertEquals(listOf(API), dns.lookups)
        assertEquals(listOf(ip(1)), session[API]?.ips)
        pool.shutdownNow()
    }

    @Test
    fun `a wait ends at its limit as a failure and a late answer clears it`() {
        newResolver(waitMillis = 1).resolveAndWait(API)
        val afterWait = isAnswerNeeded()
        dns.answer(API, ip(1))

        executor.runAll()
        now += INTERVAL

        assertFalse(afterWait)
        assertTrue(isAnswerNeeded())
        assertEquals(listOf(ip(1)), session[API]?.ips)
    }

    @Test
    fun `closing the tasks releases a waiting caller and later callers do not wait`() {
        val tasks = ResolveTasks(dns::lookup, executor) {}
        val resolver = AddressResolver(sessions, tasks, timer, true, 60_000)
        val caller = thread { resolver.resolveAndWait(API) }
        assertTrue(await { executor.queued == 1 })

        tasks.close()
        caller.join(5_000)
        val laterCaller = thread { resolver.resolveAndWait(OTHER) }
        laterCaller.join(5_000)

        assertFalse(caller.isAlive)
        assertFalse(laterCaller.isAlive)
    }

    @Test
    fun `an interrupted wait returns and keeps the interrupt`() {
        val resolver = newResolver(waitMillis = 60_000)
        Thread.currentThread().interrupt()

        resolver.resolveAndWait(API)

        assertTrue(Thread.interrupted())
        assertTrue(isAnswerNeeded())
    }

    private fun newResolver(
        preresolveOnNetworkChange: Boolean = true,
        executor: Executor = this.executor,
        waitMillis: Long = 5_000
    ) = AddressResolver(
        sessions,
        ResolveTasks(dns::lookup, executor) {},
        timer,
        preresolveOnNetworkChange,
        waitMillis
    )

    private fun isAnswerNeeded() = assertNotNull(session[API]).dns.isAnswerNeeded(timer)

    private fun await(condition: () -> Boolean): Boolean {
        repeat(500) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
        const val INTERVAL = 50L
    }
}
