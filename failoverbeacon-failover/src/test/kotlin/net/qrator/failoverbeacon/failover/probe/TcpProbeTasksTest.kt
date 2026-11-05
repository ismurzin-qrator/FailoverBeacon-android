package net.qrator.failoverbeacon.failover.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.failover.fakes.FakeTcpProbe
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.tcpResults
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.HostStates
import net.qrator.failoverbeacon.failover.network.NetworkSession

class TcpProbeTasksTest {
    private val tcpProbe = FakeTcpProbe()
    private val executor = ManualExecutor()
    private val results = mutableListOf<String>()
    private val tasks = TcpProbeTasks(tcpProbe::run, executor, onResult = { results += it })
    private val config = hostConfig(API, listOf(ip(1)), port = PORT)
    private val session = NetworkSession(
        isFirstNetwork = true,
        hostStates = HostStates(listOf(config)),
        timer = RefreshTimer({ 0L }, 100),
        networkHandle = 1L
    )

    @Test
    fun `an address is connected on the host's port and its result is saved`() {
        val connected = LayerResult.Passed(nanos = 40_000_000)
        tcpProbe.answer(ip(1), connected)

        tasks.start(session, config, ip(1))
        executor.runAll()

        assertEquals(listOf(ip(1) to PORT), tcpProbe.runs)
        assertEquals(connected, session[API]?.tcpResults?.get(ip(1)))
        assertEquals(listOf(API), results)
    }

    @Test
    fun `an address being probed is not queued again and can run after it finishes`() {
        tasks.start(session, config, ip(1))
        tasks.start(session, config, ip(1))
        val whileRunning = executor.queued
        executor.runAll()

        tasks.start(session, config, ip(1))

        assertEquals(1, whileRunning)
        assertEquals(1, executor.queued)
    }

    @Test
    fun `a queued probe of an ended session does not connect`() {
        tasks.start(session, config, ip(1))
        session.end()

        executor.runAll()

        assertEquals(emptyList(), tcpProbe.runs)
    }

    @Test
    fun `an unexpected failure keeps the earlier result and lets the address run again`() {
        val connected = LayerResult.Passed(nanos = 10)
        session.saveTcpProbe(API, ip(1), connected)
        tcpProbe.fail(ip(1), SecurityException("no INTERNET permission"))
        tasks.start(session, config, ip(1))

        runCatching { executor.runAll() }
        executor.runAll()
        tasks.start(session, config, ip(1))

        assertEquals(connected, session[API]?.tcpResults?.get(ip(1)))
        assertEquals(1, executor.queued)
    }

    private companion object {
        const val API = "api.example"
        const val PORT = 8443
    }
}
