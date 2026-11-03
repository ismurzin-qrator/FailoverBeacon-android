package net.qrator.failoverbeacon.failover.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.ConnectOutcome
import net.qrator.failoverbeacon.failover.model.HostAddress
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
    fun `answers and failures are saved with the time of the timer`() {
        now = 1_000
        session.saveAnswer(API, listOf(ip(1)))
        val afterAnswer = dns.isFresh(timer)
        now = 2_000
        session.saveResolveFailure(API)

        assertTrue(afterAnswer)
        assertFalse(dns.isStale(timer))
    }

    @Test
    fun `an ended session drops what is saved and starts no round`() {
        session.end()

        session.saveAnswer(API, listOf(ip(1)))
        session.saveProbe(API, ip(7), LayerResult.Failed)
        session.saveConnectOutcome(API, "192.0.2.7", ConnectOutcome.TLS_FAILED)
        session.saveResolveFailure(API)

        assertFalse(session.isActive)
        assertEquals(listOf(HostAddress(ip(7))), session[API]?.addresses)
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
        const val INTERVAL = 50L
    }
}
