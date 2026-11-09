package net.qrator.failoverbeacon.okhttp

import android.net.ConnectivityManager
import android.net.LinkProperties
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.FailoverHost
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.okhttp.fakes.isRetryControlSupported
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.BufferedSink
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class RetryControlTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val appClient = OkHttpClient.Builder()
        .dns { listOf(LOOPBACK) }
        .build()
    private val client = appClient.withFailoverBeacon()

    @BeforeTest
    fun setUp() {
        server.start(LOOPBACK, 0)
        val config = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(
                listOf(
                    FailoverHost(API, additionalAddresses = listOf("127.0.0.1"), tcpProbe = false)
                )
            )
            .dnsResolver { throw UnknownHostException(it) }
            .build()
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val active = assertNotNull(connectivity.activeNetwork)
        shadowOf(connectivity).apply {
            setNetworkCapabilities(active, ShadowNetworkCapabilities.newInstance())
            setLinkProperties(active, LinkProperties())
        }
        FailoverBeacon.initialize(app, config)
        assertNotNull(SdkRegistry.instance).threads.serial.submit {}.get(5, TimeUnit.SECONDS)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.clear()
        SdkRegistry.isFailoverEnabled = true
    }

    @Test
    fun `without the SDK OkHttp resends a POST after a dropped pooled connection`() {
        assertEquals(Result(isDelivered = true, requests = 3), postOnDroppedConnection(OTHER))
    }

    @Test
    fun `a POST to a failover host is sent once on OkHttp 5_4 and newer`() {
        val expected = if (isRetryControlSupported) {
            Result(isDelivered = false, requests = 2)
        } else {
            Result(isDelivered = true, requests = 3)
        }

        assertEquals(expected, postOnDroppedConnection(API))
    }

    @Test
    fun `an idempotent POST, another host and a turned off failover keep OkHttp retries`() {
        val idempotent = postOnDroppedConnection(API) { idempotent() }
        val otherHost = postOnDroppedConnection(OTHER)
        FailoverBeacon.setFailoverEnabled(false)
        val turnedOff = postOnDroppedConnection(API)

        val retried = Result(isDelivered = true, requests = 3)
        assertEquals(listOf(retried, retried, retried), listOf(idempotent, otherHost, turnedOff))
    }

    @Test
    fun `the SDK never turns on retries the client turned off`() {
        val client = appClient.newBuilder().retryOnConnectionFailure(false).build()
            .withFailoverBeacon()

        val result = postOnDroppedConnection(API, client) { idempotent() }

        assertEquals(Result(isDelivered = false, requests = 2), result)
    }

    private fun postOnDroppedConnection(
        host: String,
        client: OkHttpClient = this.client,
        customize: Request.Builder.() -> Unit = {}
    ): Result {
        val before = server.requestCount
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("retried"))
        client.newCall(Request.Builder().url(url(host)).build()).execute().use { it.body.string() }
        val post = Request.Builder()
            .url(url(host))
            .post(PAYMENT)
            .apply(customize)
            .build()
        val isDelivered = try {
            client.newCall(post).execute().use { it.body.string() }
            true
        } catch (_: IOException) {
            false
        }
        return Result(isDelivered, server.requestCount - before)
    }

    private fun url(host: String) = server.url("/").newBuilder().host(host).build()

    private data class Result(val isDelivered: Boolean, val requests: Int)

    private companion object {
        const val API = "api.test"
        const val OTHER = "other.test"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        val PAYMENT = object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("payment")
            }
        }
    }
}
