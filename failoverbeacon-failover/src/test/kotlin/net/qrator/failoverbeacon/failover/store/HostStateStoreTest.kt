package net.qrator.failoverbeacon.failover.store

import java.io.File
import java.net.InetAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.model.SavedAddress
import net.qrator.failoverbeacon.failover.model.SavedBodyProbe
import net.qrator.failoverbeacon.failover.model.SavedHost
import net.qrator.failoverbeacon.failover.model.SavedNetwork
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostStateStoreTest {
    private val dir = createTempDirectory().toFile()
    private val store = HostStateStore { dir }
    private val file = File(dir, "failoverbeacon-failover.json")

    private val networks = listOf(
        SavedNetwork(
            key = "wifi",
            hosts = listOf(
                SavedHost(
                    host = HOST,
                    answeredAtMillis = 1_794_000_000_000,
                    addresses = listOf(
                        SavedAddress(
                            address = address("203.0.113.11"),
                            isFromDns = true,
                            tcpNanos = 12_000_000,
                            bodyProbe = SavedBodyProbe(
                                isPassed = true,
                                atMillis = 1_794_000_001_000
                            )
                        ),
                        SavedAddress(
                            address = address("203.0.113.10"),
                            isFromDns = false,
                            tcpNanos = null,
                            bodyProbe = SavedBodyProbe(
                                isPassed = false,
                                atMillis = 1_794_000_002_000
                            )
                        ),
                        SavedAddress(
                            address = address("2001:db8::1"),
                            isFromDns = true,
                            tcpNanos = null,
                            bodyProbe = null
                        )
                    )
                )
            )
        ),
        SavedNetwork(
            key = "cellular",
            hosts = listOf(SavedHost("b.com", answeredAtMillis = null, addresses = emptyList()))
        )
    )

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `read returns what was written`() {
        store.write(networks)

        assertEquals(networks, HostStateStore { dir }.read())
    }

    @Test
    fun `read gives addresses the host name without a lookup`() {
        store.write(networks)

        val restored = store.read().first().hosts.first().addresses.first().address

        assertEquals(HOST, restored.hostName)
    }

    @Test
    fun `write replaces the previous state`() {
        store.write(networks)

        store.write(networks.take(1))

        assertEquals(networks.take(1), store.read())
    }

    @Test
    fun `read without a file is empty`() {
        assertEquals(emptyList(), store.read())
    }

    @Test
    fun `read of a damaged file is empty`() {
        listOf(
            "",
            "garbage",
            """{"version":1,"networks":[{"key":"wifi","hosts":[{"host":"a.com"""",
            """{"networks":[]}""",
            """{"version":1,"networks":"wifi"}""",
            """{"version":1,"networks":[{"key":"wifi"}]}""",
            damagedAddress("""{"fromDns":true}"""),
            damagedAddress("""{"ip":"cb00710b"}"""),
            damagedAddress("""{"ip":"example.com","fromDns":true}"""),
            damagedAddress("""{"ip":"cb00","fromDns":true}"""),
            damagedAddress("""{"ip":"cb00710b","fromDns":true,"bodyProbedAtMillis":1}""")
        ).forEach { content ->
            file.writeText(content)

            assertEquals(emptyList(), store.read(), content)
        }
    }

    @Test
    fun `read of an unknown version is empty`() {
        file.writeText("""{"version":2,"future":true}""")

        assertEquals(emptyList(), store.read())
    }

    @Test
    fun `failed write does not throw`() {
        val notDir = File(dir, "file").apply { writeText("") }

        val blocked = HostStateStore { notDir }
        blocked.write(networks)

        assertEquals(emptyList(), blocked.read())
    }

    @Test
    fun `clear deletes the state`() {
        store.write(networks)
        assertTrue(file.exists())

        store.clear()

        assertFalse(file.exists())
        assertEquals(emptyList(), store.read())
    }

    @Test
    fun `clear without a file does nothing`() {
        store.clear()

        assertFalse(file.exists())
    }

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun damagedAddress(address: String) =
        """{"version":1,"networks":[{"key":"wifi","hosts":[{"host":"a.com",""" +
            """"addresses":[$address]}]}]}"""

    private companion object {
        const val HOST = "a.com"
    }
}
