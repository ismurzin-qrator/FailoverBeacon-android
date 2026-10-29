package net.qrator.failoverbeacon.failover.ranking

import android.util.Log
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.FakeConnect
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostRecord
import net.qrator.failoverbeacon.failover.model.ProbeResult
import net.qrator.failoverbeacon.failover.store.AddressRecorder
import net.qrator.failoverbeacon.failover.store.AddressStore

class AddressRankingTest {
    private val log = LogRecorder()
    private val connect = FakeConnect()
    private val executor = ManualExecutor()
    private val hosts = listOf(
        FailoverHostConfig(API, PORT, listOf(ip(7)), probeConnections = true),
        FailoverHostConfig(SPARE, PORT, listOf(ip(4), ip(5)), probeConnections = true),
        FailoverHostConfig(NO_PROBES, PORT, emptyList(), probeConnections = false)
    )
    private val store = AddressStore(hosts.map { HostRecord(it.host, it.additionalAddresses) })
    private val recorder = AddressRecorder(store, clock = { 0 })
    private val ranking = AddressRanking(hosts, store, recorder, connect::connect, executor)

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `each IPv4 address is connected on the host's port and its result is recorded`() {
        connect.answer(ip(1), ProbeResult.Connected(connectNanos = 40_000_000))
        recorder.recordResolved(API, listOf(ip(1), ip(2), IPV6))

        ranking.probe(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(2), ip(7)).map { it to PORT }, connect.connects)
        assertEquals(
            mapOf(
                ip(1) to ProbeResult.Connected(connectNanos = 40_000_000),
                ip(2) to ProbeResult.Failed,
                IPV6 to ProbeResult.Unknown,
                ip(7) to ProbeResult.Failed
            ),
            store[API]?.addresses
        )
    }

    @Test
    fun `each result is logged`() {
        connect.answer(ip(1), ProbeResult.Connected(connectNanos = 40_000_000))
        recorder.recordResolved(API, listOf(ip(1)))

        ranking.probe(API)
        executor.runAll()

        assertTrue(
            Log.DEBUG to "[Failover] $API 192.0.2.1: 40 ms" in log.messages,
            "${log.messages}"
        )
        assertTrue(
            Log.DEBUG to "[Failover] $API 192.0.2.7: failed" in log.messages,
            "${log.messages}"
        )
    }

    @Test
    fun `the additional addresses are probed before DNS answers`() {
        ranking.probe(SPARE)
        executor.runAll()

        assertEquals(listOf(ip(4), ip(5)), connect.connects.map { it.first })
    }

    @Test
    fun `a host with one IPv4 address, without probes or outside the config is not probed`() {
        recorder.recordResolved(API, listOf(IPV6))
        recorder.recordResolved(NO_PROBES, listOf(ip(1), ip(2)))

        ranking.probe(API)
        ranking.probe(NO_PROBES)
        ranking.probe("unknown.example")

        assertEquals(0, executor.queued)
    }

    @Test
    fun `probed addresses are not probed again and new ones are`() {
        recorder.recordResolved(API, listOf(ip(1)))
        ranking.probe(API)
        executor.runAll()

        ranking.probe(API)
        assertEquals(0, executor.queued)
        recorder.recordResolved(API, listOf(ip(1), ip(3)))
        ranking.probe(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(7), ip(3)), connect.connects.map { it.first })
    }

    @Test
    fun `an address being probed is not queued again and a new one is queued at once`() {
        recorder.recordResolved(API, listOf(ip(1)))
        ranking.probe(API)
        ranking.probe(API)
        assertEquals(2, executor.queued)

        recorder.recordResolved(API, listOf(ip(1), ip(3)))
        ranking.probe(API)

        assertEquals(3, executor.queued)
    }

    @Test
    fun `an unexpected failure leaves the address unknown to be probed again`() {
        connect.fail(ip(1), SecurityException("no INTERNET permission"))
        recorder.recordResolved(API, listOf(ip(1)))
        ranking.probe(API)
        runCatching { executor.runAll() }
        executor.runAll()

        assertEquals(ProbeResult.Unknown, store[API]?.addresses?.get(ip(1)))
        ranking.probe(API)
        assertEquals(1, executor.queued)
    }

    private companion object {
        const val API = "api.example"
        const val SPARE = "spare.example"
        const val NO_PROBES = "noprobes.example"
        const val PORT = 8443
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
    }
}
