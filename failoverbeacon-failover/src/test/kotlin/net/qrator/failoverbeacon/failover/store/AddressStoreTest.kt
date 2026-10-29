package net.qrator.failoverbeacon.failover.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.model.HostRecord

class AddressStoreTest {
    private val store = AddressStore(listOf(HostRecord(API, additionalAddresses = listOf(ip(7)))))

    @Test
    fun `a configured host is updated in place`() {
        store.update(API) { it.withResolved(listOf(ip(1)), nowNanos = 0) }

        assertEquals(listOf(ip(1), ip(7)), store[API]?.addresses?.keys?.toList())
    }

    @Test
    fun `a host outside the config has no record and is not added`() {
        store.update(OTHER) { HostRecord(OTHER, listOf(ip(1))) }

        assertNull(store[OTHER])
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
