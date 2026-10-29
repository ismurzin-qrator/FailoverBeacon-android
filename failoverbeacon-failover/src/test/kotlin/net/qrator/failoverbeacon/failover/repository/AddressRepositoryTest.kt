package net.qrator.failoverbeacon.failover.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.ProbeResult

class AddressRepositoryTest {
    private val repository = AddressRepository(
        listOf(FailoverHostConfig(API, 443, listOf(ip(7)), probeConnections = true)),
        clock = { 10 }
    )
    private val changedHosts = mutableListOf<String>()

    @Test
    fun `a configured host starts with its additional addresses`() {
        assertEquals(mapOf(ip(7) to ProbeResult.Unknown), repository[API]?.addresses)
    }

    @Test
    fun `a resolved answer is saved with the clock time`() {
        repository.saveResolved(API, listOf(ip(1)))

        assertEquals(listOf(ip(1), ip(7)), repository[API]?.addresses?.keys?.toList())
        assertTrue(repository[API]?.isFresh(nowNanos = 10, maxAgeNanos = 0) == true)
    }

    @Test
    fun `a probe result is saved by its address`() {
        repository.saveProbe(API, ip(7), ProbeResult.Failed)

        assertEquals(mapOf(ip(7) to ProbeResult.Failed), repository[API]?.addresses)
    }

    @Test
    fun `a host outside the config is not saved`() {
        repository.saveResolved(OTHER, listOf(ip(1)))
        repository.saveProbe(OTHER, ip(1), ProbeResult.Failed)

        assertNull(repository[OTHER])
    }

    @Test
    fun `listeners hear about a host whose set of addresses changed`() {
        repository.addAddressListener { changedHosts += it }

        repository.saveResolved(API, listOf(ip(1)))
        repository.saveResolved(API, listOf(ip(1)))
        repository.saveResolved(API, emptyList())
        repository.saveProbe(API, ip(1), ProbeResult.Failed)
        repository.saveResolved(OTHER, listOf(ip(1)))
        repository.saveResolved(API, listOf(ip(2)))

        assertEquals(listOf(API, API), changedHosts)
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
