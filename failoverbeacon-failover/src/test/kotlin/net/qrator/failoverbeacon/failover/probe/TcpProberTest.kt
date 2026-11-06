package net.qrator.failoverbeacon.failover.probe

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.failover.fakes.FakeTcpProbe
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.idleBodyProber
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.fakes.tcpResults
import net.qrator.failoverbeacon.failover.model.BodyProbeConfig
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions

class TcpProberTest {
    private val tcpProbe = FakeTcpProbe()
    private val executor = ManualExecutor()
    private var now = 0L
    private val sessions = NetworkSessions(
        KnownNetworks(
            listOf(
                hostConfig(API, listOf(ip(7)), port = PORT),
                hostConfig(SPARE, listOf(ip(4), ip(5)), port = PORT),
                hostConfig(NO_PROBES, port = PORT, tcpProbe = false)
            ),
            maxNetworks = 8
        ),
        RefreshTimer({
            now
        }, INTERVAL)
    ).apply { switchTo(network(1, key = WIFI)) }
    private val prober =
        TcpProber(sessions, tcpProbe::run, executor, idleBodyProber(), probeOnNetworkChange = true)

    private val session get() = assertNotNull(sessions.current)

    @Test
    fun `the last TCP result starts the body probe of the leader`() {
        val timer = RefreshTimer({ now }, INTERVAL)
        val probed = NetworkSessions(
            KnownNetworks(
                listOf(hostConfig(API, bodyProbe = BodyProbeConfig("/probe.bin", 1_024))),
                maxNetworks = 8
            ),
            timer
        ).apply { switchTo(network(1, key = WIFI)) }
        val bodyProbes = mutableListOf<InetAddress>()
        val bodyProber = BodyProber(
            probed,
            { address, _ ->
                bodyProbes += address
                BodyProbeOutcome.PASSED
            },
            timer,
            executor,
            probeOnNetworkChange = true
        )
        val prober = TcpProber(probed, tcpProbe::run, executor, bodyProber, true)
        tcpProbe.answer(ip(1), LayerResult.Passed(nanos = 10))
        tcpProbe.answer(ip(2), LayerResult.Passed(nanos = 20))
        assertNotNull(probed.current).saveAnswer(API, listOf(ip(1), ip(2)))

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(listOf(ip(1)), bodyProbes)
    }

    @Test
    fun `each IPv4 address is connected and its result is recorded`() {
        tcpProbe.answer(ip(1), LayerResult.Passed(nanos = 40_000_000))
        session.saveAnswer(API, listOf(ip(1), ip(2), IPV6))

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(2), ip(7)).map { it to PORT }, tcpProbe.runs)
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
        tcpProbe.runs.clear()
        prober.probeIfStale(API)
        val nothingNew = executor.queued
        now += INTERVAL - 1
        session.saveAnswer(API, listOf(ip(1), ip(3)))
        prober.probeIfStale(API)
        executor.runAll()
        val newAddressOnly = tcpProbe.runs.map { it.first }
        tcpProbe.runs.clear()
        now += 1

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(0, nothingNew)
        assertEquals(listOf(ip(3)), newAddressOnly)
        assertEquals(setOf(ip(1), ip(3), ip(7)), tcpProbe.runs.map { it.first }.toSet())
    }

    @Test
    fun `an address being probed is not queued again`() {
        session.saveAnswer(API, listOf(ip(1)))

        prober.probeIfStale(API)
        prober.probeIfStale(API)

        assertEquals(2, executor.queued)
    }

    @Test
    fun `a queued probe of an ended session does not connect`() {
        session.saveAnswer(API, listOf(ip(1)))
        prober.probeIfStale(API)

        sessions.switchTo(null)
        executor.runAll()

        assertEquals(emptyList(), tcpProbe.runs)
    }

    @Test
    fun `an unexpected failure keeps the earlier result and lets the address run again`() {
        val connected = LayerResult.Passed(nanos = 10)
        session.saveAnswer(API, listOf(ip(1)))
        session.saveTcpProbe(API, ip(1), connected)
        tcpProbe.fail(ip(1), SecurityException("no INTERNET permission"))
        prober.probeIfStale(API)

        runCatching { executor.runAll() }
        executor.runAll()
        now += INTERVAL
        prober.probeIfStale(API)

        assertEquals(connected, session[API]?.tcpResults?.get(ip(1)))
        assertEquals(2, executor.queued)
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
    fun `a round cut by a network change runs again on return to that network`() {
        prober.probeIfStale(SPARE)
        executor.runAll()
        now += INTERVAL
        prober.probeIfStale(SPARE)
        sessions.switchTo(network(2, key = CELLULAR))
        executor.runAll()
        sessions.switchTo(network(3, key = WIFI))
        val runsBefore = tcpProbe.runs.size

        prober.probeIfStale(SPARE)
        executor.runAll()

        assertEquals(runsBefore + 2, tcpProbe.runs.size)
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
        val prober =
            TcpProber(
                sessions,
                tcpProbe::run,
                executor,
                idleBodyProber(),
                probeOnNetworkChange = false
            )
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
            tcpProbe.runs.map { it.first }.toSet()
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
