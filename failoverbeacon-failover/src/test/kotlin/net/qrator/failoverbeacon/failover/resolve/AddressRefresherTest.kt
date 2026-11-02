package net.qrator.failoverbeacon.failover.resolve

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.repository.AddressRepository

class AddressRefresherTest {
    private var now = 0L
    private val log = LogRecorder()
    private val resolver = FakeDns()
    private val executor = ManualExecutor()
    private val hosts = listOf(
        FailoverHostConfig(API, 443, emptyList(), probeConnections = true),
        FailoverHostConfig(OTHER, 443, emptyList(), probeConnections = true)
    )
    private val repository = AddressRepository(
        hosts,
        clock = { now },
        refreshIntervalNanos = INTERVAL,
        maxNetworks = 8
    ).apply { switchTo(network(1)) }
    private val refresher = newRefresher(preresolveOnNetworkChange = true)
    private val onlyAnsweredHosts = newRefresher(preresolveOnNetworkChange = false)

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `due hosts are resolved in the background once per interval`() {
        resolver.answer(API, ip(1))
        resolver.answer(OTHER, ip(2))

        refresher.refreshDue()
        val background = resolver.lookups.toList()
        executor.runAll()
        now += INTERVAL - 1
        refresher.refreshDue()
        val beforeInterval = executor.queued
        now += 1
        refresher.refreshDue()
        executor.runAll()

        assertEquals(emptyList(), background)
        assertEquals(0, beforeInterval)
        assertEquals(listOf(API, OTHER, API, OTHER), resolver.lookups)
    }

    @Test
    fun `a failed host waits for the interval before the next due resolution`() {
        resolver.fail(API)
        resolver.fail(OTHER)

        refresher.refreshDue()
        executor.runAll()
        refresher.refreshDue()
        val afterFailure = executor.queued
        now += INTERVAL
        refresher.refreshDue()

        assertEquals(0, afterFailure)
        assertEquals(2, executor.queued)
    }

    @Test
    fun `without a network nothing is due`() {
        repository.switchTo(null)

        refresher.refreshDue()

        assertEquals(0, executor.queued)
    }

    @Test
    fun `with preresolve off a later network refreshes only the hosts with an answer`() {
        repository.switchTo(network(2, key = "CELLULAR/001-01"))
        repository.saveResolved(2, API, listOf(ip(1)))
        now += INTERVAL

        onlyAnsweredHosts.refreshDue()
        executor.runAll()

        assertEquals(listOf(API), resolver.lookups)
    }

    @Test
    fun `with preresolve off the first network still refreshes every host`() {
        onlyAnsweredHosts.refreshDue()

        assertEquals(2, executor.queued)
    }

    @Test
    fun `preresolve takes hosts without a fresh answer`() {
        repository.saveResolved(1, API, listOf(ip(1)))

        refresher.preresolve()
        executor.runAll()

        assertEquals(listOf(OTHER), resolver.lookups)
    }

    @Test
    fun `with preresolve off a later network is not resolved on its event`() {
        onlyAnsweredHosts.preresolve()
        executor.runAll()
        val onFirstNetwork = resolver.lookups.size
        repository.switchTo(network(2, key = "CELLULAR/001-01"))

        onlyAnsweredHosts.preresolve()

        assertEquals(2, onFirstNetwork)
        assertEquals(0, executor.queued)
    }

    @Test
    fun `refresh resolves in the background into the repository`() {
        resolver.answer(API, ip(1))

        refresher.refresh(API)

        assertEquals(emptyList(), resolver.lookups)
        executor.runAll()
        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `a waiting caller returns after the answer is saved`() {
        val pool = pool()
        resolver.answer(API, ip(1))

        newRefresher(executor = pool).refreshAndWait(API)

        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
        pool.shutdownNow()
    }

    @Test
    fun `waiting callers share the resolution in progress`() {
        val pool = pool()
        val release = CountDownLatch(1)
        resolver.answer(API) {
            release.await()
            listOf(ip(1))
        }
        val refresher = newRefresher(executor = pool)
        val callers = List(2) { thread { refresher.refreshAndWait(API) } }

        assertTrue(awaitLookups(1))
        release.countDown()
        callers.forEach { it.join(5_000) }

        assertEquals(listOf(API), resolver.lookups)
        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
        pool.shutdownNow()
    }

    @Test
    fun `a wait ends at its limit as a failure and a late answer clears it`() {
        newRefresher(waitMillis = 1).refreshAndWait(API)
        val afterWait = repository[API]?.hasResolveFailed
        resolver.answer(API, ip(1))

        executor.runAll()

        assertEquals(true, afterWait)
        assertEquals(false, repository[API]?.hasResolveFailed)
        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `a failed, empty or unexpected resolution is a failure`() {
        resolver.fail(API)
        resolver.answer(OTHER) { emptyList() }
        refresher.refresh(API)
        refresher.refresh(OTHER)
        executor.runAll()
        val failed = repository[API]?.hasResolveFailed
        val empty = repository[OTHER]?.hasResolveFailed
        repository.saveResolved(1, API, listOf(ip(1)))
        resolver.answer(API) { throw SecurityException("no INTERNET permission") }
        refresher.refresh(API)

        runCatching { executor.runAll() }

        assertEquals(true, failed)
        assertEquals(true, empty)
        assertEquals(true, repository[API]?.hasResolveFailed)
    }

    @Test
    fun `closing releases a waiting caller and later callers do not wait`() {
        val refresher = newRefresher(waitMillis = 60_000)
        val caller = thread { refresher.refreshAndWait(API) }
        assertTrue(awaitQueued(1))

        refresher.close()
        caller.join(5_000)
        val laterCaller = thread { refresher.refreshAndWait(OTHER) }
        laterCaller.join(5_000)

        assertFalse(caller.isAlive)
        assertFalse(laterCaller.isAlive)
    }

    @Test
    fun `an interrupted wait returns and keeps the interrupt`() {
        val refresher = newRefresher(waitMillis = 60_000)
        Thread.currentThread().interrupt()

        refresher.refreshAndWait(API)

        assertTrue(Thread.interrupted())
        assertEquals(false, repository[API]?.hasResolveFailed)
    }

    @Test
    fun `a host is resolved once at a time`() {
        refresher.refresh(API)
        refresher.refresh(API)
        assertEquals(1, executor.queued)

        executor.runAll()
        refresher.refresh(API)
        assertEquals(1, executor.queued)
    }

    @Test
    fun `a queued resolution of an earlier session is skipped and does not block the new one`() {
        resolver.answer(API, ip(2))
        refresher.refresh(API)
        repository.switchTo(network(2))

        refresher.refresh(API)
        executor.runAll()

        assertEquals(listOf(API), resolver.lookups)
        assertEquals(listOf(ip(2)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `refreshAll logs its result when a queued resolution is skipped`() {
        refresher.refreshAll(listOf(API))
        repository.switchTo(network(2))

        executor.runAll()

        assertTrue(
            Log.INFO to "[Failover] Resolved 0 of 1 hosts" in log.messages,
            "${log.messages}"
        )
    }

    @Test
    fun `without a network nothing is resolved`() {
        repository.switchTo(null)

        refresher.refresh(API)
        refresher.refreshAndWait(API)

        assertEquals(0, executor.queued)
        assertEquals(emptyList(), resolver.lookups)
    }

    @Test
    fun `a failed resolution keeps the recorded addresses`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        resolver.fail(API)

        refresher.refresh(API)
        executor.runAll()

        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `an empty answer keeps the recorded addresses`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        resolver.answer(API) { emptyList() }

        refresher.refresh(API)
        executor.runAll()

        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `hosts beyond the free threads wait in the queue and are all resolved`() {
        val pool = pool()
        val refresher = newRefresher(executor = pool)
        resolver.answer(API, ip(1))
        resolver.answer(OTHER, ip(2))

        refresher.refreshAll(listOf(API, OTHER))
        pool.shutdown()

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
        assertEquals(listOf(ip(2)), repository[OTHER]?.addresses?.keys?.toList())
    }

    @Test
    fun `an unexpected failure lets the host be resolved again`() {
        resolver.answer(API) { throw SecurityException("no INTERNET permission") }
        refresher.refresh(API)
        runCatching { executor.runAll() }

        refresher.refresh(API)

        assertEquals(1, executor.queued)
    }

    @Test
    fun `refreshAll logs how many hosts were resolved`() {
        resolver.answer(API, ip(1))
        resolver.fail(OTHER)

        refresher.refreshAll(listOf(API, OTHER))
        executor.runAll()

        assertTrue(
            Log.INFO to "[Failover] Resolved 1 of 2 hosts" in log.messages,
            "${log.messages}"
        )
    }

    private fun newRefresher(
        preresolveOnNetworkChange: Boolean = true,
        executor: Executor = this.executor,
        waitMillis: Long = 5_000
    ) = AddressRefresher(
        resolver::lookup,
        repository,
        executor,
        hosts.map { it.host },
        preresolveOnNetworkChange,
        waitMillis
    )

    private fun pool() = SdkExecutor("test", 1, 1, 1_000, LinkedBlockingQueue())

    private fun awaitLookups(count: Int): Boolean = await { resolver.lookups.size == count }

    private fun awaitQueued(count: Int): Boolean = await { executor.queued == count }

    private fun await(condition: () -> Boolean): Boolean {
        repeat(500) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    private companion object {
        const val INTERVAL = 50L
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
