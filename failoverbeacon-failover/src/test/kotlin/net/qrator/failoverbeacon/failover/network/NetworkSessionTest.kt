package net.qrator.failoverbeacon.failover.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.ConnectOutcome
import net.qrator.failoverbeacon.failover.model.HostAddress
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.MaxAge

class NetworkSessionTest {
    private var now = 10L
    private val maxAge = MaxAge({ now }, INTERVAL)
    private val session = NetworkSession(
        hosts = NetworkHosts(listOf(HostState(hostConfig(API, listOf(ip(7)))))),
        maxAge = maxAge,
        networkHandle = 1L
    )

    private val dns get() = assertNotNull(session[API]).dns

    @Test
    fun `an ended session drops what is saved and starts no round`() {
        session.end()

        session.saveAnswer(API, listOf(ip(1)))
        session.saveTcpProbe(API, ip(7), LayerResult.Failed)
        session.saveConnectOutcome(API, "192.0.2.7", ConnectOutcome.TLS_FAILED)
        session.saveResolveFailure(API)

        assertFalse(session.isActive)
        assertEquals(listOf(HostAddress(ip(7))), session[API]?.addresses)
        assertTrue(dns.isAnswerNeeded(maxAge))
        assertNull(session.startTcpProbeRoundIfStale(API))
    }

    @Test
    fun `a probe round starts once per interval`() {
        val first = session.startTcpProbeRoundIfStale(API)
        val second = session.startTcpProbeRoundIfStale(API)
        now += INTERVAL - 1
        val beforeInterval = session.startTcpProbeRoundIfStale(API)
        now += 1

        assertNotNull(first)
        assertNull(second)
        assertNull(beforeInterval)
        assertNotNull(session.startTcpProbeRoundIfStale(API))
    }

    private companion object {
        const val API = "api.example"
        const val INTERVAL = 50L
    }
}
