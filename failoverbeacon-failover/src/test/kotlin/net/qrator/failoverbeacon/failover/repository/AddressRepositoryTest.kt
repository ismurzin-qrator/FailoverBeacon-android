package net.qrator.failoverbeacon.failover.repository

import kotlin.test.Test
import kotlin.test.assertEquals
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
        maxAgeNanos = MAX_AGE,
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
        assertTrue(repository[API]?.isFresh(nowNanos = 10, maxAgeNanos = 0) == true)
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
    fun `a stale record starts its probes over with a new answer`() {
        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveProbe(1, API, ip(1), ProbeResult.Failed)
        now += MAX_AGE + 1
        repository.saveResolved(1, API, emptyList())
        val afterFailure = repository[API]?.addresses

        repository.saveResolved(1, API, listOf(ip(1)))

        assertEquals(mapOf(ip(1) to ProbeResult.Failed, ip(7) to ProbeResult.Unknown), afterFailure)
        assertEquals(
            mapOf(ip(1) to ProbeResult.Unknown, ip(7) to ProbeResult.Unknown),
            repository[API]?.addresses
        )
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
    fun `listeners hear about a host whose addresses or their probes changed`() {
        repository.addAddressListener { changedHosts += it }

        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveResolved(1, API, listOf(ip(1)))
        repository.saveResolved(1, API, emptyList())
        repository.saveProbe(1, API, ip(1), ProbeResult.Failed)
        repository.saveResolved(1, OTHER, listOf(ip(1)))
        repository.saveResolved(2, API, listOf(ip(3)))
        now += MAX_AGE + 1
        repository.saveResolved(1, API, listOf(ip(1)))

        assertEquals(listOf(API, API), changedHosts)
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
        const val WIFI = "WIFI/192.0.2"
        const val CELLULAR = "CELLULAR/001-01"
        const val MAX_AGE = 100L
    }
}
