package net.qrator.failoverbeacon

import java.net.URISyntaxException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class FailoverBeaconConfigTest {
    @Test
    fun `defaults match documented values`() {
        val config = builderWithBeacon().build()

        assertEquals(listOf(BEACON), config.beacons)
        assertEquals(emptySet(), config.blockedHosts)
        assertEquals(1.0, config.failureSampleRate)
        assertEquals(0.1, config.successSampleRate)
        assertEquals(60_000L, config.options.uploadPeriodMillis)
        assertEquals(1000, config.options.diskQueueSize)
        assertEquals(1000, config.options.memoryQueueSize)
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
    fun `beacons rejects non-https urls`() {
        for (url in listOf("http://beacon.example", "ftp://beacon.example", "beacon.example")) {
            assertFailsWith<IllegalArgumentException>(url) {
                FailoverBeaconConfig.Builder().beacons(listOf(url))
            }
        }
    }

    @Test
    fun `beacons rejects url without host`() {
        assertFailsWith<IllegalArgumentException> {
            FailoverBeaconConfig.Builder().beacons(listOf("https:///path"))
        }
    }

    @Test
    fun `beacons rejects malformed url with parse error as cause`() {
        val e = assertFailsWith<IllegalArgumentException> {
            FailoverBeaconConfig.Builder().beacons(listOf("https://bad host"))
        }

        assertIs<URISyntaxException>(e.cause)
    }

    @Test
    fun `beacons rejects list if any url is invalid`() {
        assertFailsWith<IllegalArgumentException> {
            FailoverBeaconConfig.Builder().beacons(listOf(BEACON, "http://beacon.example"))
        }
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
    fun `beacons are copied`() {
        val beacons = mutableListOf(BEACON)
        val builder = FailoverBeaconConfig.Builder().beacons(beacons)

        beacons.add("https://other.example")

        assertEquals(listOf(BEACON), builder.build().beacons)
    }

    @Test
    fun `blockedHosts are lowercased`() {
        val config = builderWithBeacon()
            .blockedHosts(listOf("Analytics.Example", "ads.example"))
            .build()

        assertEquals(setOf("analytics.example", "ads.example"), config.blockedHosts)
    }

    @Test
    fun `blockedHosts take internationalized names in Unicode or ASCII`() {
        val config = builderWithBeacon()
            .blockedHosts(listOf("Пример.РФ", "xn--e1afmkfd.xn--p1ai"))
            .build()

        assertEquals(setOf("xn--e1afmkfd.xn--p1ai"), config.blockedHosts)
    }

    @Test
    fun `blockedHosts rejects blank names, urls and invalid names`() {
        val tooLongLabel = "a".repeat(64) + ".example"
        val hosts =
            listOf(
                "",
                " ",
                "https://analytics.example",
                "analytics.example/path",
                "a b",
                tooLongLabel
            )
        for (host in hosts) {
            assertFailsWith<IllegalArgumentException>(host) {
                builderWithBeacon().blockedHosts(listOf(host))
            }
        }
    }

    @Test
    fun `blockedHosts are copied`() {
        val hosts = mutableListOf("analytics.example")
        val builder = builderWithBeacon().blockedHosts(hosts)

        hosts.add("ads.example")

        assertEquals(setOf("analytics.example"), builder.build().blockedHosts)
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

    @Test
    fun `options are kept`() {
        val options = FailoverBeaconOptions.Builder().diskQueueSize(10).build()

        val config = builderWithBeacon().options(options).build()

        assertSame(options, config.options)
    }

    private fun builderWithBeacon() = FailoverBeaconConfig.Builder().beacons(listOf(BEACON))

    private companion object {
        const val BEACON = "https://beacon.example"
    }
}
