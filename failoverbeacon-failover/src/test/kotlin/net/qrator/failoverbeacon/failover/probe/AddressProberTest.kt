package net.qrator.failoverbeacon.failover.probe

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.failover.fakes.FakeConnect
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.fakes.tcpResults
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.NetworkSessions

class AddressProberTest {
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

    private val session get() = assertNotNull(sessions.current)

    @Test
    fun `each IPv4 address is connected and its result is recorded`() {
        connect.answer(ip(1), LayerResult.Passed(nanos = 40_000_000))
        session.saveAnswer(API, listOf(ip(1), ip(2), IPV6))

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(2), ip(7)).map { it to PORT }, connect.connects)
        assertEquals(
            mapOf(
                ip(1) to LayerResult.Passed(nanos = 40_000_000),
                ip(2) to LayerResult.Failed,
                IPV6 to LayerResult.Unknown,
                ip(7) to LayerResult.Failed
            ),
            session[API]?.tcpResults
        )
    }

    @Test
    fun `a host with one IPv4 address, without probes or a network, or unknown is not probed`() {
        session.saveAnswer(API, listOf(IPV6))
        session.saveAnswer(NO_PROBES, listOf(ip(1), ip(2)))

        prober.probeIfStale(API)
        prober.probeIfStale(NO_PROBES)
        prober.probeIfStale("unknown.example")
        sessions.switchTo(null)
        prober.probeIfStale(SPARE)
        prober.probeStaleHosts()

        assertEquals(0, executor.queued)
    }

    @Test
    fun `a new address is probed at once and the rest wait for the interval`() {
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)
        executor.runAll()
        connect.connects.clear()
        prober.probeIfStale(API)
        val nothingNew = executor.queued
        now += INTERVAL - 1
        session.saveAnswer(API, listOf(ip(1), ip(3)))
        prober.probeIfStale(API)
        executor.runAll()
        val newAddressOnly = connect.connects.map { it.first }
        connect.connects.clear()
        now += 1

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(0, nothingNew)
        assertEquals(listOf(ip(3)), newAddressOnly)
        assertEquals(setOf(ip(1), ip(3), ip(7)), connect.connects.map { it.first }.toSet())
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
    fun `with network change probes off the first network is probed, others are not`() {
        val prober = AddressProber(sessions, tasks, probeOnNetworkChange = false)
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)
        val onFirstNetwork = executor.queued
        executor.runAll()
        sessions.switchTo(network(2, key = CELLULAR))
        session.saveAnswer(API, listOf(ip(1)))

        prober.probeIfStale(API)

        assertEquals(2, onFirstNetwork)
        assertEquals(0, executor.queued)
    }

    @Test
    fun `probing stale hosts covers every host that allows probes, also before DNS answers`() {
        session.saveAnswer(API, listOf(ip(1)))
        session.saveAnswer(NO_PROBES, listOf(ip(2), ip(3)))

        prober.probeStaleHosts()
        executor.runAll()

        assertEquals(
            setOf(ip(1), ip(7), ip(4), ip(5)),
            connect.connects.map { it.first }.toSet()
        )
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
