package net.qrator.failoverbeacon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailoverHostTest {
    @Test
    fun `defaults match documented values`() {
        val host = FailoverHost("api.example")

        assertEquals("api.example", host.host)
        assertEquals(443, host.port)
        assertEquals(emptyList(), host.additionalAddresses)
        assertTrue(host.tcpProbe)
    }

    @Test
    fun `host is kept in lowercase ASCII, internationalized names in either form`() {
        assertEquals("api.example", FailoverHost("Api.EXAMPLE").host)
        assertEquals("xn--e1afmkfd.xn--p1ai", FailoverHost("Пример.РФ").host)
        assertEquals("xn--e1afmkfd.xn--p1ai", FailoverHost("xn--e1afmkfd.xn--p1ai").host)
    }

    @Test
    fun `host rejects urls, ports, paths, user info and malformed names`() {
        val hosts =
            listOf(
                "",
                " ",
                " api.example",
                "https://api.example",
                "api.example:443",
                "api.example/path",
                "user@api.example",
                "api example",
                "api<1>.example",
                "api^.example",
                "api|.example",
                "api.example.",
                ".api.example",
                "api..example",
                "a".repeat(64) + ".example",
                "a.".repeat(127) + "example"
            )
        for (host in hosts) {
            assertFailsWith<IllegalArgumentException>(host) { FailoverHost(host) }
        }
    }

    @Test
    fun `host rejects IP addresses`() {
        for (host in listOf("192.0.2.10", "1.2.3", "api.0x1F", "[::1]", "::1")) {
            assertFailsWith<IllegalArgumentException>(host) { FailoverHost(host) }
        }
    }

    @Test
    fun `port accepts 1 to 65535`() {
        assertEquals(1, FailoverHost("api.example", 1).port)
        assertEquals(65535, FailoverHost("api.example", 65535).port)
        for (port in listOf(0, -1, 65536)) {
            assertFailsWith<IllegalArgumentException>("$port") { FailoverHost("api.example", port) }
        }
    }

    @Test
    fun `additionalAddresses accepts IPv4 and drops duplicates in order`() {
        val host = FailoverHost(
            "api.example",
            additionalAddresses = listOf("192.0.2.10", "0.0.0.0", "255.255.255.255", "192.0.2.10")
        )

        assertEquals(
            listOf<String?>("192.0.2.10", "0.0.0.0", "255.255.255.255"),
            host.additionalAddresses.map { it.hostAddress }
        )
        assertTrue(host.additionalAddresses.all { it.hostName == "api.example" })
    }

    @Test
    fun `additionalAddresses rejects values that are not IPv4 addresses`() {
        val addresses =
            listOf(
                "",
                "256.0.0.1",
                "1.2.3",
                "1.2.3.4.5",
                "01.2.3.4",
                "1.2.3.4:443",
                " 1.2.3.4",
                "+1.2.3.4",
                "::1",
                "2001:db8::1",
                "api.example",
                "١.٢.٣.٤"
            )
        for (address in addresses) {
            assertFailsWith<IllegalArgumentException>(address) {
                FailoverHost("api.example", additionalAddresses = listOf(address))
            }
        }
    }

    @Test
    fun `additionalAddresses is a copy of the given list`() {
        val addresses = mutableListOf("192.0.2.10")
        val host = FailoverHost("api.example", additionalAddresses = addresses)

        addresses += "192.0.2.11"

        assertEquals(listOf<String?>("192.0.2.10"), host.additionalAddresses.map { it.hostAddress })
    }

    @Test
    fun `bodyProbe is off by default and needs tcpProbe`() {
        assertNull(FailoverHost("api.example").bodyProbe)
        assertFailsWith<IllegalArgumentException> {
            FailoverHost("api.example", tcpProbe = false, bodyProbe = FailoverBodyProbe())
        }
    }

    @Test
    fun `body probe defaults to the root and 16 KiB`() {
        val probe = FailoverBodyProbe()

        assertEquals("/", probe.path)
        assertEquals(16 * 1024, probe.maxBytes)
    }

    @Test
    fun `body probe path keeps a query and rejects what breaks the request line`() {
        assertEquals("/a/b.bin?x=1", FailoverBodyProbe("/a/b.bin?x=1").path)
        for (path in listOf("", "a", " /", "/a b", "/a\r\nX: 1", "/#a", "/пример")) {
            assertFailsWith<IllegalArgumentException>(path) { FailoverBodyProbe(path) }
        }
    }

    @Test
    fun `body probe limit accepts 1 KiB to 1 MiB`() {
        assertEquals(1024, FailoverBodyProbe(maxBytes = 1024).maxBytes)
        assertEquals(1024 * 1024, FailoverBodyProbe(maxBytes = 1024 * 1024).maxBytes)
        for (maxBytes in listOf(0, 1023, 1024 * 1024 + 1)) {
            assertFailsWith<IllegalArgumentException>("$maxBytes") {
                FailoverBodyProbe(maxBytes = maxBytes)
            }
        }
    }
}
