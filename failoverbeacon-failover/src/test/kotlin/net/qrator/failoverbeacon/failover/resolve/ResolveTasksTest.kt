package net.qrator.failoverbeacon.failover.resolve

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.failover.fakes.FakeDns
import net.qrator.failoverbeacon.failover.fakes.LogRecorder
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.HostStates
import net.qrator.failoverbeacon.failover.network.NetworkSession

class ResolveTasksTest {
    private val log = LogRecorder()
    private val resolver = FakeDns()
    private val executor = ManualExecutor()
    private val answers = mutableListOf<String>()
    private val timer = RefreshTimer({ 0L }, 100)
    private val tasks = ResolveTasks(resolver::lookup, executor) { answers += it }
    private val session = session()

    @AfterTest
    fun tearDown() = log.close()

    @Test
    fun `an answer is saved in the background and announced`() {
        resolver.answer(API, ip(1))

        tasks.start(session, API)
        val beforeRun = resolver.lookups.toList()
        executor.runAll()

        assertEquals(emptyList(), beforeRun)
        assertEquals(listOf(ip(1)), session[API]?.ips)
        assertEquals(listOf(API), answers)
    }

    @Test
    fun `callers finish when the task is done`() {
        resolver.answer(API, ip(1))
        val done = tasks.start(session, API)
        val beforeRun = done.count

        executor.runAll()

        assertEquals(1, beforeRun)
        assertEquals(0, done.count)
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
    fun `a failed resolution keeps the addresses and is recorded`() {
        session.saveAnswer(API, listOf(ip(1)))
        resolver.fail(API)

        tasks.start(session, API)
        executor.runAll()

        assertEquals(listOf(ip(1)), session[API]?.ips)
        assertFalse(isAnswerNeeded())
        assertEquals(emptyList(), answers)
    }

    @Test
    fun `an empty answer keeps the addresses and is recorded as a failure`() {
        session.saveAnswer(API, listOf(ip(1)))
        resolver.answer(API) { emptyList() }

        tasks.start(session, API)
        executor.runAll()

        assertEquals(listOf(ip(1)), session[API]?.ips)
        assertFalse(isAnswerNeeded())
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
    fun `hosts beyond the free threads wait in the queue and are all resolved`() {
        val pool = SdkExecutor("test", 1, 1, 1_000, LinkedBlockingQueue())
        val tasks = ResolveTasks(resolver::lookup, pool) {}
        resolver.answer(API, ip(1))
        resolver.answer(OTHER, ip(2))

        tasks.start(session, API)
        tasks.start(session, OTHER)
        pool.shutdown()

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf(ip(1)), session[API]?.ips)
        assertEquals(listOf(ip(2)), session[OTHER]?.ips)
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
        hostStates = HostStates(listOf(hostConfig(API), hostConfig(OTHER))),
        timer = timer,
        networkHandle = 1L
    )

    private fun isAnswerNeeded() = assertNotNull(session[API]).dns.isAnswerNeeded(timer)

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
