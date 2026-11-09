package net.qrator.failoverbeacon.failover.resolve

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.FakeTcpProbe
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.idleBodyProber
import net.qrator.failoverbeacon.failover.fakes.idleTcpProber
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.MaxAge
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkHosts
import net.qrator.failoverbeacon.failover.network.NetworkSession
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.TcpProber

class ResolveTasksTest {
    private val previousOutput = SdkRegistry.logger.output
    private val resolver = FakeDns()
    private val executor = ManualExecutor()
    private var now = 0L
    private val maxAge = MaxAge({ now }, 100)
    private val tasks = ResolveTasks(resolver::lookup, executor, idleTcpProber())
    private val session = session()

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.clear()
    }

    @Test
    fun `an answer is saved in the background and finishes the callers`() {
        resolver.answer(API, ip(1))

        val done = tasks.start(session, API)
        val beforeRun = resolver.lookups.toList()
        val countBeforeRun = done.count
        executor.runAll()

        assertEquals(emptyList(), beforeRun)
        assertEquals(1, countBeforeRun)
        assertEquals(0, done.count)
        assertEquals(listOf(ip(1)), session[API]?.ips)
    }

    @Test
    fun `an answer starts the TCP probes of the host, a failure or an empty answer does not`() {
        val tcpProbe = FakeTcpProbe()
        val probes = ManualExecutor()
        val sessions = NetworkSessions(
            KnownNetworks(listOf(hostConfig(API, listOf(ip(1), ip(2)))), 8),
            maxAge
        )
        sessions.switchTo(network(1))
        val session = assertNotNull(sessions.current)
        session.startTcpProbeRoundIfStale(API)
        val tcpProber = TcpProber(sessions, tcpProbe::run, probes, idleBodyProber())
        val tasks = ResolveTasks(resolver::lookup, executor, tcpProber)

        resolver.fail(API)
        tasks.start(session, API)
        executor.runAll()
        resolver.answer(API) { emptyList() }
        tasks.start(session, API)
        executor.runAll()
        val queuedWithoutAnswer = probes.queued
        resolver.answer(API, ip(3))
        tasks.start(session, API)
        executor.runAll()
        probes.runAll()

        assertEquals(0, queuedWithoutAnswer)
        assertEquals(setOf(ip(1), ip(2), ip(3)), tcpProbe.runs.map { it.first }.toSet())
    }

    @Test
    fun `a host is resolved once at a time`() {
        val first = tasks.start(session, API)

        val second = tasks.start(session, API)

        assertSame(first, second)
        assertEquals(1, executor.queued)
        executor.runAll()
        tasks.start(session, API)
        assertEquals(1, executor.queued)
    }

    @Test
    fun `a task of an ended session is skipped and does not block the new one`() {
        resolver.answer(API, ip(2))
        tasks.start(session, API)
        session.end()
        val next = session()

        tasks.start(next, API)
        executor.runAll()

        assertEquals(listOf(API), resolver.lookups)
        assertEquals(listOf(ip(2)), next[API]?.ips)
    }

    @Test
    fun `a failed or empty answer keeps the addresses and is recorded`() {
        session.saveAnswer(API, listOf(ip(1)))
        now = 100

        resolver.fail(API)
        tasks.start(session, API)
        executor.runAll()
        val afterFailure = isAnswerNeeded()
        session.saveAnswer(API, listOf(ip(1)))
        now = 200
        resolver.answer(API) { emptyList() }
        tasks.start(session, API)
        executor.runAll()

        assertFalse(afterFailure)
        assertFalse(isAnswerNeeded())
        assertEquals(listOf(ip(1)), session[API]?.ips)
    }

    @Test
    fun `a failing app resolver is recorded and logged once as a warning with the host`() {
        val logged = CopyOnWriteArrayList<Pair<Int, String>>()
        SdkRegistry.logger.output = LogOutput { level, message, _ -> logged += level to message }
        resolver.answer(API) { throw SecurityException("no INTERNET permission") }

        repeat(2) {
            tasks.start(session, API)
            executor.runAll()
        }

        assertFalse(isAnswerNeeded())
        assertEquals(listOf(API, API), resolver.lookups)
        assertEquals(
            listOf(
                Log.WARN to "[Failover] $API: dnsResolver failed. Further failures are not logged"
            ),
            logged
        )
    }

    private fun session() = NetworkSession(
        hosts = NetworkHosts(listOf(HostState(hostConfig(API)), HostState(hostConfig(OTHER)))),
        maxAge = maxAge,
        networkHandle = 1L
    )

    private fun isAnswerNeeded() = assertNotNull(session[API]).dns.isAnswerNeeded(maxAge)

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
