package net.qrator.failoverbeacon

import java.net.InetAddress
import java.net.URISyntaxException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FailoverBeaconConfigTest {
    @Test
    fun `defaults match documented values`() {
        val config = builderWithBeacon().build()

        assertEquals(listOf(BEACON), config.beacons)
        assertEquals(emptySet(), config.blockedHosts)
        assertEquals(1.0, config.failureSampleRate)
        assertEquals(0.1, config.successSampleRate)
        assertEquals(emptyList(), config.failoverHosts)
    }

    @Test
    fun `build requires beacons`() {
        assertFailsWith<IllegalArgumentException> { FailoverBeaconConfig.Builder().build() }
    }

    @Test
    fun `beacons rejects empty list`() {
        assertFailsWith<IllegalArgumentException> {
            FailoverBeaconConfig.Builder().beacons(emptyList())
        }
    }

    @Test
    fun `beacons rejects urls that are not https or have no host`() {
        val urls =
            listOf(
                "http://beacon.example",
                "ftp://beacon.example",
                "beacon.example",
                "https:///path"
            )
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
    fun `beacons rejects malformed url with parse error as cause`() {
        val e = assertFailsWith<IllegalArgumentException> {
            FailoverBeaconConfig.Builder().beacons(listOf("https://bad host"))
        }

        assertIs<URISyntaxException>(e.cause)
    }

    @Test
    fun `beacons accepts scheme in any case, path and port`() {
        val urls = listOf("HTTPS://beacon.example", "https://beacon.example:8443/reports")

        val config = FailoverBeaconConfig.Builder().beacons(urls).build()

        assertEquals(urls, config.beacons)
    }

    @Test
    fun `beacons drops duplicates and keeps order`() {
        val config = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://b.example", "https://a.example", "https://b.example"))
            .build()

        assertEquals(listOf("https://b.example", "https://a.example"), config.beacons)
    }

    @Test
    fun `blockedHosts are kept in lowercase ASCII, internationalized names in either form`() {
        val config = builderWithBeacon()
            .blockedHosts(listOf("Analytics.Example", "Пример.РФ", "xn--e1afmkfd.xn--p1ai"))
            .build()

        assertEquals(setOf("analytics.example", "xn--e1afmkfd.xn--p1ai"), config.blockedHosts)
    }

    @Test
    fun `blockedHosts accepts IPv4 addresses`() {
        val config = builderWithBeacon().blockedHosts(listOf("192.0.2.10")).build()

        assertEquals(setOf("192.0.2.10"), config.blockedHosts)
    }

    @Test
    fun `blockedHosts rejects blank names, urls, ports and invalid names`() {
        val tooLongLabel = "a".repeat(64) + ".example"
        val hosts =
            listOf(
                "",
                " ",
                "https://analytics.example",
                "analytics.example/path",
                "a b",
                "analytics.example:443",
                "user@analytics.example",
                "analytics.example.",
                tooLongLabel
            )
        for (host in hosts) {
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
    fun `failoverHosts rejects the same host twice in any form`() {
        val repeats =
            listOf(
                listOf(FailoverHost("api.example"), FailoverHost("api.example", 8443)),
                listOf(FailoverHost("api.example"), FailoverHost("API.example")),
                listOf(FailoverHost("Пример.рф"), FailoverHost("xn--e1afmkfd.xn--p1ai"))
            )
        for (hosts in repeats) {
            assertFailsWith<IllegalArgumentException> { builderWithBeacon().failoverHosts(hosts) }
        }
    }

    @Test
    fun `dnsResolver is the system DNS by default and can be replaced`() {
        val appDns = HostResolver { listOf(InetAddress.getByName("192.0.2.1")) }

        val default = builderWithBeacon().build().dnsResolver
        val replaced = builderWithBeacon().dnsResolver(appDns).build().dnsResolver

        assertTrue(default.resolve("localhost").all { it.isLoopbackAddress })
        assertSame(appDns, replaced)
    }

    @Test
    fun `sample rates accept bounds`() {
        val config = builderWithBeacon()
            .failureSampleRate(0.0)
            .successSampleRate(1.0)
            .build()

        assertEquals(0.0, config.failureSampleRate)
        assertEquals(1.0, config.successSampleRate)
    }

    @Test
    fun `sample rates reject values outside 0 to 1 and NaN`() {
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
