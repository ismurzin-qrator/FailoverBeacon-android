package net.qrator.failoverbeacon.okhttp

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.FailoverHost
import net.qrator.failoverbeacon.core.SdkRegistry
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FailoverEndToEndTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder()
        .dns { appDns(it) }
        .build()
        .withFailoverBeacon()

    @BeforeTest
    fun setUp() {
        server.start(LOOPBACK, 0)
        val config = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(
                listOf(
                    FailoverHost(API, additionalAddresses = listOf("127.0.0.1")),
                    FailoverHost(RANKED, server.port, listOf(UNREACHABLE, "127.0.0.1"))
                )
            )
            .build()
        FailoverBeacon.initialize(app, config)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.clear()
    }

    @Test
    fun `a configured host is reached by its additional address when the app's DNS fails`() {
        server.enqueue(MockResponse().setBody("ok"))

        assertEquals("ok", execute(API))
    }

    @Test
    fun `a new connection starts with the address that answered the probe`() {
        val connects = CopyOnWriteArrayList<InetAddress>()
        val client = OkHttpClient.Builder()
            .dns { appDns(it) }
            .eventListener(
                object : EventListener() {
                    override fun connectStart(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy
                    ) {
                        connects += inetSocketAddress.address
                    }
                }
            )
            .build()
            .withFailoverBeacon()
        server.enqueue(MockResponse().setBody("ok"))
        assertTrue(awaitLeader(RANKED, LOOPBACK), "probe of $RANKED did not finish")

        assertEquals("ok", execute(RANKED, client))
        assertEquals(LOOPBACK, connects.first())
    }

    @Test
    fun `other hosts keep the app's DNS answer and error`() {
        server.enqueue(MockResponse().setBody("ok"))

        assertEquals("ok", execute(OTHER))
        assertFailsWith<UnknownHostException> { execute("missing.test") }
    }

    @Test
    fun `after stop a configured host gets the app's DNS error`() {
        FailoverBeacon.stop()

        assertFailsWith<UnknownHostException> { execute(API) }
    }

    private fun appDns(host: String): List<InetAddress> =
        if (host == OTHER) listOf(LOOPBACK) else throw UnknownHostException(host)

    private fun awaitLeader(host: String, address: InetAddress): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (SdkRegistry.instance?.dns?.onResolveFailed(host)?.first() == address) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun execute(host: String, client: OkHttpClient = this.client): String {
        val url = server.url("/").newBuilder().host(host).build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
    }

    private companion object {
        const val API = "api.test"
        const val OTHER = "other.test"
        const val RANKED = "ranked.test"
        const val UNREACHABLE = "192.0.2.1"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
