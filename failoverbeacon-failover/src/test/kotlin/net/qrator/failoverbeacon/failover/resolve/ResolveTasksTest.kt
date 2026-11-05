package net.qrator.failoverbeacon.failover.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.NetworkSession
import net.qrator.failoverbeacon.failover.network.NetworkState

class ResolveTasksTest {
    private val resolver = FakeDns()
    private val executor = ManualExecutor()
    private val answers = mutableListOf<String>()
    private var now = 0L
    private val timer = RefreshTimer({ now }, 100)
    private val tasks = ResolveTasks(resolver::lookup, executor) { answers += it }
    private val session = session()

    @Test
    fun `an answer is saved in the background, announced and finishes the callers`() {
        resolver.answer(API, ip(1))

        val done = tasks.start(session, API)
        val beforeRun = resolver.lookups.toList()
        val countBeforeRun = done.count
        executor.runAll()

        assertEquals(emptyList(), beforeRun)
        assertEquals(1, countBeforeRun)
        assertEquals(0, done.count)
        assertEquals(listOf(ip(1)), session[API]?.ips)
        assertEquals(listOf(API), answers)
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
        assertEquals(emptyList(), answers)
    }

    @Test
    fun `an unexpected failure is recorded and lets the host be resolved again`() {
        resolver.answer(API) { throw SecurityException("no INTERNET permission") }
        tasks.start(session, API)

        runCatching { executor.runAll() }
        tasks.start(session, API)

        assertFalse(isAnswerNeeded())
        assertEquals(1, executor.queued)
    }

    @Test
    fun `closing finishes running tasks and later ones do not start`() {
        val running = tasks.start(session, API)

        tasks.close()
        val later = tasks.start(session, OTHER)

        assertEquals(0, running.count)
        assertEquals(0, later.count)
        assertEquals(1, executor.queued)
    }

    private fun session() = NetworkSession(
        isFirstNetwork = true,
        state = NetworkState(listOf(HostState(hostConfig(API)), HostState(hostConfig(OTHER)))),
        timer = timer,
        networkHandle = 1L
    )

    private fun isAnswerNeeded() = assertNotNull(session[API]).dns.isAnswerNeeded(timer)

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
