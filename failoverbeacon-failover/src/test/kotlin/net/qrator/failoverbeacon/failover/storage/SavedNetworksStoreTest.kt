package net.qrator.failoverbeacon.failover.storage

import java.io.File
import java.net.InetAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.failover.model.SavedAddress
import net.qrator.failoverbeacon.failover.model.SavedBodyProbe
import net.qrator.failoverbeacon.failover.model.SavedHost
import net.qrator.failoverbeacon.failover.model.SavedNetwork
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedNetworksStoreTest {
    private val dir = createTempDirectory().toFile()
    private val store = SavedNetworksStore { dir }
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

        assertEquals(networks, SavedNetworksStore { dir }.read())
    }

    @Test
    fun `read gives addresses the host name without a lookup`() {
        store.write(networks)

        val restored = store.read().first().hosts.first().addresses.first().address

        assertEquals(HOST, restored.hostName)
    }

    @Test
    fun `write replaces the previous state, also with nothing`() {
        store.write(networks)
        store.write(networks.take(1))
        val replaced = SavedNetworksStore { dir }.read()

        store.write(emptyList())

        assertEquals(networks.take(1), replaced)
        assertEquals(emptyList(), SavedNetworksStore { dir }.read())
    }

    @Test
    fun `the same content is written again after clear`() {
        store.write(networks)
        store.clear()

        store.write(networks)

        assertEquals(networks, store.read())
    }

    @Test
    fun `a failed write is tried again`() {
        val notDir = File(dir, "blocked").apply { writeText("") }
        val blocked = SavedNetworksStore { notDir }
        blocked.write(networks)
        notDir.delete()
        notDir.mkdir()

        blocked.write(networks)

        assertEquals(networks, blocked.read())
    }

    @Test
    fun `read without a file is empty`() {
        assertEquals(emptyList(), store.read())
    }

    @Test
    fun `read of a damaged file is empty`() {
        listOf(
            "garbage",
            """{"version":1,"networks":[{"key":"wifi","hosts":[{"host":"a.com"""",
            """{"networks":[]}""",
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

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun damagedAddress(address: String) =
        """{"version":1,"networks":[{"key":"wifi","hosts":[{"host":"a.com",""" +
            """"addresses":[$address]}]}]}"""

    private companion object {
        const val HOST = "a.com"
    }
}
