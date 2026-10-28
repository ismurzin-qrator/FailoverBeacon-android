package net.qrator.failoverbeacon.failover.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.qrator.failoverbeacon.failover.fakes.ip

class AddressCacheTest {
    private var now = 0L
    private val cache = AddressCache({ now }, maxAgeNanos = 100)

    @Test
    fun `addresses are fresh up to the max age`() {
        cache.put("api.example", listOf(ip(1)))

        now = 100
        assertEquals(listOf(ip(1)), cache.freshAddresses("api.example"))
        now = 101
        assertNull(cache.freshAddresses("api.example"))
    }

    @Test
    fun `the last addresses stay after the max age`() {
        cache.put("api.example", listOf(ip(1)))
        now = 1_000

        assertEquals(listOf(ip(1)), cache.lastAddresses("api.example"))
    }

    @Test
    fun `a new put replaces the addresses and their age`() {
        cache.put("api.example", listOf(ip(1)))
        now = 90
        cache.put("api.example", listOf(ip(2)))
        now = 150

        assertEquals(listOf(ip(2)), cache.freshAddresses("api.example"))
    }

    @Test
    fun `an unknown host has no addresses`() {
        assertNull(cache.freshAddresses("api.example"))
        assertNull(cache.lastAddresses("api.example"))
    }
}
