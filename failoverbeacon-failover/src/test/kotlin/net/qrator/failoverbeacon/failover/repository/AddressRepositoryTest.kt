package net.qrator.failoverbeacon.failover.repository

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.ProbeResult

class AddressRepositoryTest {
    private var now = 10L
    private val repository = AddressRepository(
        listOf(FailoverHostConfig(API, 443, listOf(ip(7)), probeConnections = true)),
        clock = { now },
        refreshIntervalNanos = INTERVAL,
        maxNetworks = 2
    ).apply { switchTo(network(1, key = WIFI)) }
    private val changedHosts = mutableListOf<String>()

    @Test
    fun `a configured host starts with its additional addresses`() {
        assertEquals(mapOf(ip(7) to ProbeResult.Unknown), repository[API]?.addresses)
    }

    @Test
    fun `a resolved answer is saved with the clock time`() {
        repository.saveResolved(1, API, listOf(ip(1)))

        assertEquals(listOf(ip(1), ip(7)), repository[API]?.addresses?.keys?.toList())
        assertTrue(repository[API]?.isFresh(nowNanos = 10, intervalNanos = 1) == true)
    }

    @Test
    fun `a probe result is saved by its address`() {
        repository.saveProbe(1, API, ip(7), ProbeResult.Failed)

        assertEquals(mapOf(ip(7) to ProbeResult.Failed), repository[API]?.addresses)
    }

    @Test
    fun `a host outside the config is not saved`() {
        repository.saveResolved(1, OTHER, listOf(ip(1)))
        repository.saveProbe(1, OTHER, ip(1), ProbeResult.Failed)

        assertNull(repository[OTHER])
    }

    @Test
    fun `a stale record keeps its probes with a new answer`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveProbe(1, API, ip(1), ProbeResult.Failed)
        now += INTERVAL + 1

        repository.saveResolved(1, API, listOf(ip(1)))

        assertEquals(
            mapOf(ip(7) to ProbeResult.Unknown, ip(1) to ProbeResult.Failed),
            repository[API]?.addresses
        )
    }

    @Test
    fun `an answer is needed when none is fresh unless the last resolution failed`() {
        val withoutAnswer = repository.isAnswerNeeded(assertNotNull(repository[API]))
        repository.saveResolved(1, API, listOf(ip(1)))
        val fresh = repository.isAnswerNeeded(assertNotNull(repository[API]))
        now += INTERVAL
        val stale = repository.isAnswerNeeded(assertNotNull(repository[API]))
        repository.saveResolveFailure(1, API)
        val failed = repository.isAnswerNeeded(assertNotNull(repository[API]))
        repository.saveResolved(1, API, listOf(ip(1)))
        now += INTERVAL

        assertTrue(withoutAnswer)
        assertFalse(fresh)
        assertTrue(stale)
        assertFalse(failed)
        assertTrue(repository.isAnswerNeeded(assertNotNull(repository[API])))
    }

    @Test
    fun `a resolve failure of an earlier session is dropped`() {
        repository.switchTo(network(2, key = WIFI))

        repository.saveResolveFailure(1, API)

        assertFalse(assertNotNull(repository[API]).hasResolveFailed)
    }

    @Test
    fun `a resolve round starts once per interval and an answer counts as one`() {
        val first = repository.startResolveRound(1, API)
        val second = repository.startResolveRound(1, API)
        now += INTERVAL
        repository.saveResolved(1, API, listOf(ip(1)))
        val afterAnswer = repository.startResolveRound(1, API)
        now += INTERVAL

        assertTrue(first)
        assertFalse(second)
        assertFalse(afterAnswer)
        assertTrue(repository.startResolveRound(1, API))
    }

    @Test
    fun `a probe round starts once per interval`() {
        val first = repository.startProbeRound(1, API)
        val second = repository.startProbeRound(1, API)
        now += INTERVAL - 1
        val beforeInterval = repository.startProbeRound(1, API)
        now += 1

        assertTrue(first)
        assertFalse(second)
        assertFalse(beforeInterval)
        assertTrue(repository.startProbeRound(1, API))
    }

    @Test
    fun `parallel callers start one probe round`() {
        val callers = 16
        val pool = Executors.newFixedThreadPool(callers)
        val start = CountDownLatch(1)
        val rounds = List(callers) {
            pool.submit<Boolean> {
                start.await()
                repository.startProbeRound(1, API)
            }
        }

        start.countDown()

        assertEquals(1, rounds.count { it.get() })
        pool.shutdown()
    }

    @Test
    fun `a probe round of an earlier session or an unknown host does not start`() {
        repository.switchTo(network(2, key = WIFI))

        assertFalse(repository.startProbeRound(1, API))
        assertFalse(repository.startProbeRound(2, OTHER))
    }

    @Test
    fun `each network keeps its own probe round`() {
        repository.startProbeRound(1, API)
        repository.switchTo(network(2, key = CELLULAR))

        assertTrue(repository.startProbeRound(2, API))
    }

    @Test
    fun `without a network there is nothing to read or save`() {
        repository.switchTo(null)

        repository.saveResolved(1, API, listOf(ip(1)))

        assertNull(repository[API])
        assertNull(repository.currentHandle)
    }

    @Test
    fun `each network keeps its own addresses`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        repository.switchTo(network(2, key = CELLULAR))
        val onCellular = repository[API]?.addresses?.keys?.toList()

        repository.switchTo(network(3, key = WIFI))

        assertEquals(listOf(ip(7)), onCellular)
        assertEquals(listOf(ip(1), ip(7)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `results of an earlier session are dropped`() {
        repository.switchTo(network(2, key = WIFI))

        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveProbe(1, API, ip(7), ProbeResult.Failed)

        assertEquals(mapOf(ip(7) to ProbeResult.Unknown), repository[API]?.addresses)
    }

    @Test
    fun `beyond the limit the least recently used network is dropped`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        repository.switchTo(network(2, key = CELLULAR))
        repository.saveResolved(2, API, listOf(ip(2)))
        repository.switchTo(network(3, key = WIFI))
        repository.switchTo(network(4, key = "ETHERNET/198.51.100"))

        repository.switchTo(network(5, key = WIFI))
        val wifi = repository[API]?.addresses?.keys?.toList()
        repository.switchTo(network(6, key = CELLULAR))

        assertEquals(listOf(ip(1), ip(7)), wifi)
        assertEquals(listOf(ip(7)), repository[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `only the session the repository started with is on the first network`() {
        val onStart = repository.isOnFirstNetwork
        repository.switchTo(null)
        val withoutNetwork = repository.isOnFirstNetwork
        repository.switchTo(network(2, key = WIFI))
        val onReconnect = repository.isOnFirstNetwork
        repository.switchTo(network(1, key = WIFI))

        assertTrue(onStart)
        assertFalse(withoutNetwork)
        assertFalse(onReconnect)
        assertTrue(repository.isOnFirstNetwork)
    }

    @Test
    fun `listeners hear about a host whose addresses changed`() {
        repository.addAddressListener { changedHosts += it }

        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveResolved(1, API, emptyList())
        repository.saveProbe(1, API, ip(1), ProbeResult.Failed)
        repository.saveResolved(1, OTHER, listOf(ip(1)))
        repository.saveResolved(2, API, listOf(ip(3)))
        now += INTERVAL + 1
        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveResolved(1, API, listOf(ip(1), ip(3)))

        assertEquals(listOf(API, API), changedHosts)
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
        const val WIFI = "WIFI/192.0.2"
        const val CELLULAR = "CELLULAR/001-01"
        const val INTERVAL = 50L
    }
}
