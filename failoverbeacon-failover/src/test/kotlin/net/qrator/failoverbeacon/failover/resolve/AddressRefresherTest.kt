package net.qrator.failoverbeacon.failover.resolve

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.repository.AddressRepository

class AddressRefresherTest {
    private val log = LogRecorder()
    private val resolver = FakeDns()
    private val executor = ManualExecutor()
    private val hosts = listOf(
        FailoverHostConfig(API, 443, emptyList(), probeConnections = true),
        FailoverHostConfig(OTHER, 443, emptyList(), probeConnections = true)
    )
    private val repository = AddressRepository(hosts, clock = { 0 })
    private val refresher = AddressRefresher(resolver::lookup, repository, executor)

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `refresh resolves in the background into the repository`() {
        resolver.answer(API, ip(1))

        refresher.refresh(API)

        assertEquals(emptyList(), resolver.lookups)
        executor.runAll()
        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
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
    fun `a failed resolution keeps the recorded addresses`() {
        repository.saveResolved(API, listOf(ip(1)))
        resolver.fail(API)

        refresher.refresh(API)
        executor.runAll()

        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `an empty answer keeps the recorded addresses`() {
        repository.saveResolved(API, listOf(ip(1)))
        resolver.answer(API) { emptyList() }

        refresher.refresh(API)
        executor.runAll()

        assertEquals(listOf(ip(1)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `hosts beyond the free threads wait in the queue and are all resolved`() {
        val pool = SdkExecutor("test", 1, 1, 1_000, LinkedBlockingQueue())
        val refresher = AddressRefresher(resolver::lookup, repository, pool)
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

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
