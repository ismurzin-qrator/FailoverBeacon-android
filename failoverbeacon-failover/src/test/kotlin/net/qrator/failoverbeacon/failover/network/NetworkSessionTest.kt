package net.qrator.failoverbeacon.failover.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.tcpResults
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer

class NetworkSessionTest {
    private var now = 10L
    private val timer = RefreshTimer({ now }, INTERVAL)
    private val session = NetworkSession(
        isFirstNetwork = true,
        hostStates = HostStates(listOf(hostConfig(API, listOf(ip(7))))),
        timer = timer,
        networkHandle = 1L
    )

    private val dns get() = assertNotNull(session[API]).dns

    @Test
    fun `an answer is saved with the time of the timer`() {
        session.saveAnswer(API, listOf(ip(1)))
        val afterAnswer = dns.isFresh(timer)
        now += INTERVAL

        assertEquals(listOf(ip(1), ip(7)), session[API]?.ips)
        assertTrue(afterAnswer)
        assertFalse(dns.isFresh(timer))
    }

    @Test
    fun `a resolve failure is saved with the time of the timer`() {
        session.saveResolveFailure(API)
        val afterFailure = dns.isStale(timer)
        now += INTERVAL

        assertFalse(afterFailure)
        assertTrue(dns.isStale(timer))
    }

    @Test
    fun `a probe result is saved by its address`() {
        session.saveProbe(API, ip(7), LayerResult.Failed)

        assertEquals(mapOf(ip(7) to LayerResult.Failed), session[API]?.tcpResults)
    }

    @Test
    fun `a stale state keeps its probes with a new answer`() {
        session.saveAnswer(API, listOf(ip(1)))
        session.saveProbe(API, ip(1), LayerResult.Failed)
        now += INTERVAL + 1

        session.saveAnswer(API, listOf(ip(1)))

        assertEquals(
            mapOf(ip(7) to LayerResult.Unknown, ip(1) to LayerResult.Failed),
            session[API]?.tcpResults
        )
    }

    @Test
    fun `a host outside the config is not saved`() {
        session.saveAnswer(OTHER, listOf(ip(1)))
        session.saveProbe(OTHER, ip(1), LayerResult.Failed)
        session.saveResolveFailure(OTHER)

        assertNull(session[OTHER])
        assertFalse(session.startProbeRoundIfStale(OTHER))
    }

    @Test
    fun `an ended session drops what is saved and starts no round`() {
        session.end()

        session.saveAnswer(API, listOf(ip(1)))
        session.saveProbe(API, ip(7), LayerResult.Failed)
        session.saveResolveFailure(API)

        assertFalse(session.isActive)
        assertEquals(mapOf(ip(7) to LayerResult.Unknown), session[API]?.tcpResults)
        assertTrue(dns.isAnswerNeeded(timer))
        assertFalse(session.startProbeRoundIfStale(API))
    }

    @Test
    fun `a probe round starts once per interval`() {
        val first = session.startProbeRoundIfStale(API)
        val second = session.startProbeRoundIfStale(API)
        now += INTERVAL - 1
        val beforeInterval = session.startProbeRoundIfStale(API)
        now += 1

        assertTrue(first)
        assertFalse(second)
        assertFalse(beforeInterval)
        assertTrue(session.startProbeRoundIfStale(API))
    }

    @Test
    fun `parallel callers start one probe round`() {
        val callers = 16
        val pool = Executors.newFixedThreadPool(callers)
        val start = CountDownLatch(1)
        val rounds = List(callers) {
            pool.submit<Boolean> {
                start.await()
                session.startProbeRoundIfStale(API)
            }
        }

        start.countDown()

        assertEquals(1, rounds.count { it.get() })
        pool.shutdown()
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
        const val INTERVAL = 50L
    }
}
