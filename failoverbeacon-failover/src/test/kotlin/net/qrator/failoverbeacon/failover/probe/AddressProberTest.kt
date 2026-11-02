package net.qrator.failoverbeacon.failover.probe

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
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.ProbeResult
import net.qrator.failoverbeacon.failover.repository.AddressRepository

class AddressProberTest {
    private val log = LogRecorder()
    private val connect = FakeConnect()
    private val executor = ManualExecutor()
    private val hosts = listOf(
        FailoverHostConfig(API, PORT, listOf(ip(7)), probeConnections = true),
        FailoverHostConfig(SPARE, PORT, listOf(ip(4), ip(5)), probeConnections = true),
        FailoverHostConfig(NO_PROBES, PORT, emptyList(), probeConnections = false)
    )
    private var now = 0L
    private val repository = AddressRepository(
        hosts,
        { now },
        MAX_AGE,
        INTERVAL,
        maxNetworks = 8
    ).apply { switchTo(network(1, key = WIFI)) }
    private val prober = AddressProber(hosts, repository, connect::connect, executor, true)
    private val firstNetworkProber =
        AddressProber(hosts, repository, connect::connect, executor, false)

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `each IPv4 address is connected on the host's port and its result is recorded`() {
        connect.answer(ip(1), ProbeResult.Connected(connectNanos = 40_000_000))
        repository.saveResolved(1, API, listOf(ip(1), ip(2), IPV6))

        prober.probe(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(2), ip(7)).map { it to PORT }, connect.connects)
        assertEquals(
            mapOf(
                ip(1) to ProbeResult.Connected(connectNanos = 40_000_000),
                ip(2) to ProbeResult.Failed,
                IPV6 to ProbeResult.Unknown,
                ip(7) to ProbeResult.Failed
            ),
            repository[API]?.addresses
        )
    }

    @Test
    fun `each result is logged`() {
        connect.answer(ip(1), ProbeResult.Connected(connectNanos = 40_000_000))
        repository.saveResolved(1, API, listOf(ip(1)))

        prober.probe(API)
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
        prober.probe(SPARE)
        executor.runAll()

        assertEquals(listOf(ip(4), ip(5)), connect.connects.map { it.first })
    }

    @Test
    fun `a host with one IPv4 address, without probes or outside the config is not probed`() {
        repository.saveResolved(1, API, listOf(IPV6))
        repository.saveResolved(1, NO_PROBES, listOf(ip(1), ip(2)))

        prober.probe(API)
        prober.probe(NO_PROBES)
        prober.probe("unknown.example")

        assertEquals(0, executor.queued)
    }

    @Test
    fun `probed addresses are not probed again and new ones are`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        executor.runAll()

        prober.probe(API)
        assertEquals(0, executor.queued)
        repository.saveResolved(1, API, listOf(ip(1), ip(3)))
        prober.probe(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(7), ip(3)), connect.connects.map { it.first })
    }

    @Test
    fun `an address being probed is not queued again and a new one is queued at once`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        prober.probe(API)
        assertEquals(2, executor.queued)

        repository.saveResolved(1, API, listOf(ip(1), ip(3)))
        prober.probe(API)

        assertEquals(3, executor.queued)
    }

    @Test
    fun `a new session of a known network keeps its results`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        executor.runAll()
        repository.switchTo(network(2, key = WIFI))

        prober.probe(API)

        assertEquals(0, executor.queued)
    }

    @Test
    fun `a new network is probed while a probe of the earlier one is running`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        repository.switchTo(network(2, key = CELLULAR))
        repository.saveResolved(2, API, listOf(ip(1)))

        prober.probe(API)

        assertEquals(4, executor.queued)
    }

    @Test
    fun `a queued probe of an earlier session does not connect and can run again`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        repository.switchTo(network(2, key = WIFI))

        executor.runAll()
        val connectsOfEarlierSession = connect.connects.size
        repository.switchTo(network(1, key = WIFI))
        prober.probe(API)

        assertEquals(0, connectsOfEarlierSession)
        assertEquals(
            mapOf(ip(1) to ProbeResult.Unknown, ip(7) to ProbeResult.Unknown),
            repository[API]?.addresses
        )
        assertEquals(2, executor.queued)
    }

    @Test
    fun `with network change probes off the first network is probed, others are not`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        firstNetworkProber.probe(API)
        executor.runAll()
        now += INTERVAL
        firstNetworkProber.probe(API)
        val afterInterval = executor.queued
        executor.runAll()
        repository.switchTo(network(2, key = CELLULAR))
        repository.saveResolved(2, API, listOf(ip(1)))

        firstNetworkProber.probe(API)

        assertEquals(2, afterInterval)
        assertEquals(0, executor.queued)
    }

    @Test
    fun `after the interval every IPv4 address is probed again`() {
        repository.saveResolved(1, API, listOf(ip(1), IPV6))
        prober.probe(API)
        executor.runAll()
        connect.connects.clear()
        now += INTERVAL - 1
        prober.probe(API)
        val beforeInterval = executor.queued
        now += 1

        prober.probe(API)
        executor.runAll()

        assertEquals(0, beforeInterval)
        assertEquals(listOf(ip(1), ip(7)), connect.connects.map { it.first })
    }

    @Test
    fun `earlier results keep the order until new ones replace them`() {
        val fast = ProbeResult.Connected(connectNanos = 10)
        val slow = ProbeResult.Connected(connectNanos = 20)
        connect.answer(ip(1), fast)
        connect.answer(ip(7), slow)
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        executor.runAll()
        connect.answer(ip(1), ProbeResult.Failed)
        now += INTERVAL

        prober.probe(API)
        val duringRound = repository[API]?.addresses
        executor.runAll()

        assertEquals(mapOf(ip(1) to fast, ip(7) to slow), duringRound)
        assertEquals(listOf(ip(1), ip(7)), duringRound?.keys?.toList())
        assertEquals(listOf(ip(7), ip(1)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `a new address is probed at once and does not move the next round`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        executor.runAll()
        connect.connects.clear()
        now += INTERVAL - 1
        repository.saveResolved(1, API, listOf(ip(1), ip(3)))
        prober.probe(API)
        executor.runAll()
        val newAddressOnly = connect.connects.map { it.first }
        connect.connects.clear()
        now += 1

        prober.probe(API)
        executor.runAll()

        assertEquals(listOf(ip(3)), newAddressOnly)
        assertEquals(setOf(ip(1), ip(3), ip(7)), connect.connects.map { it.first }.toSet())
    }

    @Test
    fun `an address still being probed is not queued again by the next round`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        now += INTERVAL

        prober.probe(API)

        assertEquals(2, executor.queued)
    }

    @Test
    fun `an unexpected failure keeps the earlier result`() {
        val connected = ProbeResult.Connected(connectNanos = 10)
        connect.answer(ip(1), connected)
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        executor.runAll()
        connect.fail(ip(1), SecurityException("no INTERNET permission"))
        now += INTERVAL
        prober.probe(API)

        runCatching { executor.runAll() }
        executor.runAll()

        assertEquals(connected, repository[API]?.addresses?.get(ip(1)))
    }

    @Test
    fun `probing all covers every host that allows probes`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveResolved(1, NO_PROBES, listOf(ip(2), ip(3)))

        prober.probeAll()
        executor.runAll()

        assertEquals(
            setOf(ip(1), ip(7), ip(4), ip(5)),
            connect.connects.map { it.first }.toSet()
        )
    }

    @Test
    fun `without a network nothing is probed`() {
        repository.switchTo(null)

        prober.probe(SPARE)

        assertEquals(0, executor.queued)
    }

    @Test
    fun `an unexpected failure leaves the address unknown to be probed again`() {
        connect.fail(ip(1), SecurityException("no INTERNET permission"))
        repository.saveResolved(1, API, listOf(ip(1)))
        prober.probe(API)
        runCatching { executor.runAll() }
        executor.runAll()

        assertEquals(ProbeResult.Unknown, repository[API]?.addresses?.get(ip(1)))
        prober.probe(API)
        assertEquals(1, executor.queued)
    }

    private companion object {
        const val API = "api.example"
        const val SPARE = "spare.example"
        const val NO_PROBES = "noprobes.example"
        const val PORT = 8443
        const val MAX_AGE = 100L
        const val INTERVAL = 50L
        const val WIFI = "WIFI/192.0.2"
        const val CELLULAR = "CELLULAR/001-01"
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
    }
}
