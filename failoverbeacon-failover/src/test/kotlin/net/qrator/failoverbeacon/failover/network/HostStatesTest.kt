package net.qrator.failoverbeacon.failover.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips

class HostStatesTest {
    private val hostStates = HostStates(listOf(hostConfig(API, listOf(ip(7))), hostConfig(OTHER)))

    @Test
    fun `configured hosts start in the config order`() {
        assertEquals(listOf(API, OTHER), hostStates.all.map { it.config.host })
        assertEquals(listOf(ip(7)), hostStates[API]?.ips)
    }

    @Test
    fun `an update changes a configured host and ignores others`() {
        hostStates.update(API) { it.withDns(it.dns.withAnswer(listOf(ip(1)), atNanos = 0)) }
        hostStates.update("unknown.example") { error("no such host") }

        assertEquals(listOf(ip(1), ip(7)), hostStates[API]?.ips)
        assertNull(hostStates["unknown.example"])
    }

    private companion object {
        const val API = "api.example"
        const val OTHER = "other.example"
    }
}
