package net.qrator.failoverbeacon.okhttp

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.FailoverHost
import net.qrator.failoverbeacon.core.SdkRegistry
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
        .dns { host -> if (host == OTHER) listOf(LOOPBACK) else throw UnknownHostException(host) }
        .build()
        .withFailoverBeacon()

    @BeforeTest
    fun setUp() {
        server.start(LOOPBACK, 0)
        val config = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(listOf(FailoverHost(API, additionalAddresses = listOf("127.0.0.1"))))
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

    private fun execute(host: String): String {
        val url = server.url("/").newBuilder().host(host).build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
    }

    private companion object {
        const val API = "api.test"
        const val OTHER = "other.test"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
