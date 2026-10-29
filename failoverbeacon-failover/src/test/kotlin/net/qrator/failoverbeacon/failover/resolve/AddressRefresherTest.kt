package net.qrator.failoverbeacon.failover.resolve

import android.util.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.FakeConnect
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostRecord
import net.qrator.failoverbeacon.failover.ranking.AddressRanking
import net.qrator.failoverbeacon.failover.store.AddressRecorder
import net.qrator.failoverbeacon.failover.store.AddressStore

class AddressRefresherTest {
    private val log = LogRecorder()
    private val resolver = FakeDns()
    private val connect = FakeConnect()
    private val executor = ManualExecutor()
    private val probeExecutor = ManualExecutor()
    private val hosts = listOf(
        FailoverHostConfig(API, 443, emptyList(), probeConnections = true),
        FailoverHostConfig(OTHER, 443, emptyList(), probeConnections = true)
    )
    private val store = AddressStore(hosts.map { HostRecord(it.host, it.additionalAddresses) })
    private val recorder = AddressRecorder(store, clock = { 0 })
    private val ranking = AddressRanking(hosts, store, recorder, connect::connect, probeExecutor)
    private val refresher = AddressRefresher(resolver::lookup, recorder, ranking, executor)

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `refresh resolves in the background into the store`() {
        resolver.answer(API, ip(1))

        refresher.refresh(API)

        assertEquals(emptyList(), resolver.lookups)
        executor.runAll()
        assertEquals(listOf(ip(1)), store[API]?.addresses?.keys?.toList())
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
        recorder.recordResolved(API, listOf(ip(1)))
        resolver.fail(API)

        refresher.refresh(API)
        executor.runAll()

        assertEquals(listOf(ip(1)), store[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `an empty answer keeps the recorded addresses`() {
        recorder.recordResolved(API, listOf(ip(1)))
        resolver.answer(API) { emptyList() }

        refresher.refresh(API)
        executor.runAll()

        assertEquals(listOf(ip(1)), store[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `a busy pool skips the resolution and lets the next refresh run`() {
        executor.busy = true
        refresher.refresh(API)
        executor.busy = false
        refresher.refresh(API)

        assertEquals(1, executor.queued)
        assertTrue(log.messages.any { "$API: no free thread" in it.second }, "${log.messages}")
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

    @Test
    fun `a recorded answer probes its new addresses`() {
        refresher.recordAnswer(API, listOf(ip(1), ip(2)))
        probeExecutor.runAll()

        assertEquals(listOf(ip(1), ip(2)), connect.connects.map { it.first })
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
