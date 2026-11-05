package net.qrator.failoverbeacon.failover.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.RefreshTimer

class NetworkSessionsTest {
    private val sessions = NetworkSessions(
        listOf(hostConfig(API, listOf(ip(7)))),
        RefreshTimer({ 10L }, intervalNanos = 50),
        maxNetworks = 2
    ).apply { switchTo(network(1, key = WIFI)) }

    private val session get() = assertNotNull(sessions.current)

    @Test
    fun `each network keeps its own addresses and probe round`() {
        session.saveAnswer(API, listOf(ip(1)))
        session.startTcpProbeRoundIfStale(API)
        sessions.switchTo(network(2, key = CELLULAR))
        val onCellular = session[API]?.ips
        val roundOnCellular = session.startTcpProbeRoundIfStale(API)

        sessions.switchTo(network(3, key = WIFI))

        assertEquals(listOf(ip(7)), onCellular)
        assertTrue(roundOnCellular)
        assertEquals(listOf(ip(1), ip(7)), session[API]?.ips)
    }

    @Test
    fun `switching or losing the network ends the session`() {
        val earlier = session
        sessions.switchTo(network(1, key = WIFI))
        val next = session

        sessions.switchTo(null)
        earlier.saveAnswer(API, listOf(ip(1)))

        assertFalse(earlier.isActive)
        assertFalse(next.isActive)
        assertNull(sessions.current)
        sessions.switchTo(network(1, key = WIFI))
        assertEquals(listOf(ip(7)), session[API]?.ips)
    }

    @Test
    fun `beyond the limit the least recently used network is dropped`() {
        session.saveAnswer(API, listOf(ip(1)))
        sessions.switchTo(network(2, key = CELLULAR))
        session.saveAnswer(API, listOf(ip(2)))
        sessions.switchTo(network(3, key = WIFI))
        sessions.switchTo(network(4, key = "ETHERNET/198.51.100"))

        sessions.switchTo(network(5, key = WIFI))
        val wifi = session[API]?.ips
        sessions.switchTo(network(6, key = CELLULAR))

        assertEquals(listOf(ip(1), ip(7)), wifi)
        assertEquals(listOf(ip(7)), session[API]?.ips)
    }

    @Test
    fun `the first network is the first one that appears`() {
        val onStart = session.isFirstNetwork
        sessions.switchTo(network(2, key = WIFI))
        val onReconnect = session.isFirstNetwork
        sessions.switchTo(network(1, key = WIFI))
        val startedWithout = NetworkSessions(emptyList(), RefreshTimer({ 0L }, 50), maxNetworks = 2)

        startedWithout.switchTo(null)
        startedWithout.switchTo(network(5, key = WIFI))

        assertTrue(onStart)
        assertFalse(onReconnect)
        assertTrue(session.isFirstNetwork)
        assertTrue(assertNotNull(startedWithout.current).isFirstNetwork)
    }

    private companion object {
        const val API = "api.example"
        const val WIFI = "WIFI/192.0.2"
        const val CELLULAR = "CELLULAR/001-01"
    }
}
