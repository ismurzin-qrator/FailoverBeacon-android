package net.qrator.failoverbeacon.failover.probe

import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.failover.fakes.FakeConnect
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.fakes.probes
import net.qrator.failoverbeacon.failover.model.ProbeResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.NetworkSessions

class AddressProberTest {
    private val log = LogRecorder()
    private val connect = FakeConnect()
    private val executor = ManualExecutor()
    private var now = 0L
    private val sessions = NetworkSessions(
        listOf(
            hostConfig(API, listOf(ip(7)), port = PORT),
            hostConfig(SPARE, listOf(ip(4), ip(5)), port = PORT),
            hostConfig(NO_PROBES, port = PORT, probeConnections = false)
        ),
        RefreshTimer({ now }, INTERVAL),
        maxNetworks = 8
    ).apply { switchTo(network(1, key = WIFI)) }
    private val tasks = ProbeTasks(connect::connect, executor)
    private val prober = AddressProber(sessions, tasks, probeOnNetworkChange = true)
    private val firstNetworkProber = AddressProber(sessions, tasks, probeOnNetworkChange = false)

    private val session get() = assertNotNull(sessions.current)

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `each IPv4 address is connected and its result is recorded`() {
        connect.answer(ip(1), ProbeResult.Connected(connectNanos = 40_000_000))
        session.saveAnswer(API, listOf(ip(1), ip(2), IPV6))

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(2), ip(7)).map { it to PORT }, connect.connects)
        assertEquals(
            mapOf(
                ip(1) to ProbeResult.Connected(connectNanos = 40_000_000),
                ip(2) to ProbeResult.Failed,
                IPV6 to ProbeResult.Unknown,
                ip(7) to ProbeResult.Failed
            ),
            session[API]?.probes
        )
    }

    @Test
    fun `the additional addresses are probed before DNS answers`() {
        prober.probeIfStale(SPARE)
        executor.runAll()

        assertEquals(listOf(ip(4), ip(5)), connect.connects.map { it.first })
    }

    @Test
    fun `a host with one IPv4 address, without probes or outside the config is not probed`() {
        session.saveAnswer(API, listOf(IPV6))
        session.saveAnswer(NO_PROBES, listOf(ip(1), ip(2)))

        prober.probeIfStale(API)
        prober.probeIfStale(NO_PROBES)
        prober.probeIfStale("unknown.example")

        assertEquals(0, executor.queued)
    }

    @Test
    fun `probed addresses are not probed again and new ones are`() {
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)
        executor.runAll()

        prober.probeIfStale(API)
        assertEquals(0, executor.queued)
        session.saveAnswer(API, listOf(ip(1), ip(3)))
        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(7), ip(3)), connect.connects.map { it.first })
    }

    @Test
    fun `a new session of a known network keeps its results`() {
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)
        executor.runAll()
        sessions.switchTo(network(2, key = WIFI))

        prober.probeIfStale(API)

        assertEquals(0, executor.queued)
    }

    @Test
    fun `a new network is probed while a probe of the earlier one is running`() {
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)
        sessions.switchTo(network(2, key = CELLULAR))
        session.saveAnswer(API, listOf(ip(1)))

        prober.probeIfStale(API)

        assertEquals(4, executor.queued)
    }

    @Test
    fun `a probe that outlives a reconnect of the same network records nothing`() {
        session.saveAnswer(API, listOf(ip(1)))
        connect.answer(ip(1), ProbeResult.Failed)
        val earlier = session
        prober.probeIfStale(API)
        sessions.switchTo(null)
        sessions.switchTo(network(1, key = WIFI))

        executor.runAll()

        assertEquals(false, earlier.isActive)
        assertEquals(0, connect.connects.size)
        assertEquals(
            mapOf(ip(1) to ProbeResult.Unknown, ip(7) to ProbeResult.Unknown),
            session[API]?.probes
        )
    }

    @Test
    fun `with network change probes off the first network is probed, others are not`() {
        session.saveAnswer(API, listOf(ip(1)))
        firstNetworkProber.probeIfStale(API)
        executor.runAll()
        now += INTERVAL
        firstNetworkProber.probeIfStale(API)
        val afterInterval = executor.queued
        executor.runAll()
        sessions.switchTo(network(2, key = CELLULAR))
        session.saveAnswer(API, listOf(ip(1)))

        firstNetworkProber.probeIfStale(API)

        assertEquals(2, afterInterval)
        assertEquals(0, executor.queued)
    }

    @Test
    fun `after the interval every IPv4 address is probed again`() {
        session.saveAnswer(API, listOf(ip(1), IPV6))
        prober.probeIfStale(API)
        executor.runAll()
        connect.connects.clear()
        now += INTERVAL - 1
        prober.probeIfStale(API)
        val beforeInterval = executor.queued
        now += 1

        prober.probeIfStale(API)
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
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)
        executor.runAll()
        connect.answer(ip(1), ProbeResult.Failed)
        now += INTERVAL

        prober.probeIfStale(API)
        val duringRound = assertNotNull(session[API])
        executor.runAll()

        assertEquals(mapOf(ip(1) to fast, ip(7) to slow), duringRound.probes)
        assertEquals(listOf(ip(1), ip(7)), duringRound.ips)
        assertEquals(listOf(ip(7), ip(1)), session[API]?.ips)
    }

    @Test
    fun `a new address is probed at once and does not move the next round`() {
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)
        executor.runAll()
        connect.connects.clear()
        now += INTERVAL - 1
        session.saveAnswer(API, listOf(ip(1), ip(3)))
        prober.probeIfStale(API)
        executor.runAll()
        val newAddressOnly = connect.connects.map { it.first }
        connect.connects.clear()
        now += 1

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(listOf(ip(3)), newAddressOnly)
        assertEquals(setOf(ip(1), ip(3), ip(7)), connect.connects.map { it.first }.toSet())
    }

    @Test
    fun `an address still being probed is not queued again by the next round`() {
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)
        now += INTERVAL

        prober.probeIfStale(API)

        assertEquals(2, executor.queued)
    }

    @Test
    fun `probing stale hosts covers every host that allows probes`() {
        session.saveAnswer(API, listOf(ip(1)))
        session.saveAnswer(NO_PROBES, listOf(ip(2), ip(3)))

        prober.probeStaleHosts()
        executor.runAll()

        assertEquals(
            setOf(ip(1), ip(7), ip(4), ip(5)),
            connect.connects.map { it.first }.toSet()
        )
    }

    @Test
    fun `without a network nothing is probed`() {
        sessions.switchTo(null)

        prober.probeIfStale(SPARE)
        prober.probeStaleHosts()

        assertEquals(0, executor.queued)
    }

    private companion object {
        const val API = "api.example"
        const val SPARE = "spare.example"
        const val NO_PROBES = "noprobes.example"
        const val PORT = 8443
        const val INTERVAL = 50L
        const val WIFI = "WIFI/192.0.2"
        const val CELLULAR = "CELLULAR/001-01"
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
    }
}
