package net.qrator.failoverbeacon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailoverBeaconConfigTest {
    @Test
    fun `defaults match documented values`() {
        val config = builderWithBeacon().build()

        assertEquals(emptySet(), config.blockedHosts)
        assertEquals(1.0, config.failureSampleRate)
        assertEquals(0.1, config.successSampleRate)
        assertEquals(emptyList(), config.failoverHosts)
        assertTrue(config.dnsResolver.resolve("localhost").all { it.isLoopbackAddress })
    }

    @Test
    fun `beacons are required and must not be empty`() {
        assertFailsWith<IllegalArgumentException> { FailoverBeaconConfig.Builder().build() }
        assertFailsWith<IllegalArgumentException> {
            FailoverBeaconConfig.Builder().beacons(emptyList())
        }
    }

    @Test
    fun `beacons rejects urls that are not https, have no host or do not parse`() {
        val urls = listOf("http://beacon.example", "beacon.example", "https:///path", "https://a b")
        for (url in urls) {
            assertFailsWith<IllegalArgumentException>(url) {
                FailoverBeaconConfig.Builder().beacons(listOf(url))
            }
        }
    }

    @Test
    fun `beacons rejects user info without repeating it`() {
        val e = assertFailsWith<IllegalArgumentException> {
            FailoverBeaconConfig.Builder().beacons(listOf("https://user:s3cr3t@beacon.example"))
        }

        assertFalse("s3cr3t" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `beacons keep a scheme in any case, a path and a port, without duplicates in order`() {
        val urls = listOf(
            "HTTPS://b.example",
            "https://a.example:8443/reports",
            "HTTPS://b.example"
        )

        val config = FailoverBeaconConfig.Builder().beacons(urls).build()

        assertEquals(listOf("HTTPS://b.example", "https://a.example:8443/reports"), config.beacons)
    }

    @Test
    fun `blockedHosts are kept in lowercase ASCII, internationalized names in either form`() {
        val config = builderWithBeacon()
            .blockedHosts(
                listOf("Analytics.Example", "Пример.РФ", "xn--e1afmkfd.xn--p1ai", "192.0.2.10")
            )
            .build()

        assertEquals(
            setOf("analytics.example", "xn--e1afmkfd.xn--p1ai", "192.0.2.10"),
            config.blockedHosts
        )
    }

    @Test
    fun `blockedHosts rejects blank names, urls and names with a port`() {
        for (host in listOf(" ", "https://analytics.example", "analytics.example:443")) {
            assertFailsWith<IllegalArgumentException>(host) {
                builderWithBeacon().blockedHosts(listOf(host))
            }
        }
    }

    @Test
    fun `failoverHosts keeps hosts in order and is a copy of the given list`() {
        val api = FailoverHost("api.example")
        val cdn = FailoverHost("cdn.example", 8443)
        val hosts = mutableListOf(api, cdn)

        val config = builderWithBeacon().failoverHosts(hosts).build()
        hosts.clear()

        assertEquals(listOf(api, cdn), config.failoverHosts)
    }

    @Test
    fun `failoverHosts rejects the same host twice, also with another port or case`() {
        val repeats =
            listOf(
                listOf(FailoverHost("api.example"), FailoverHost("api.example", 8443)),
                listOf(FailoverHost("api.example"), FailoverHost("API.example"))
            )
        for (hosts in repeats) {
            assertFailsWith<IllegalArgumentException> { builderWithBeacon().failoverHosts(hosts) }
        }
    }

    @Test
    fun `sample rates accept 0 to 1 and reject other values and NaN`() {
        val config = builderWithBeacon().failureSampleRate(0.0).successSampleRate(1.0).build()

        assertEquals(0.0, config.failureSampleRate)
        assertEquals(1.0, config.successSampleRate)
        for (rate in listOf(-0.1, 1.1, Double.NaN)) {
            assertFailsWith<IllegalArgumentException>("failure $rate") {
                builderWithBeacon().failureSampleRate(rate)
            }
            assertFailsWith<IllegalArgumentException>("success $rate") {
                builderWithBeacon().successSampleRate(rate)
            }
        }
    }

    private fun builderWithBeacon() = FailoverBeaconConfig.Builder().beacons(listOf(BEACON))

    private companion object {
        const val BEACON = "https://beacon.example"
    }
}
