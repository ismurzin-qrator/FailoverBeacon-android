package net.qrator.failoverbeacon.okhttp

import android.net.ConnectivityManager
import android.net.LinkProperties
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.FailoverHost
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.FailoverMark
import net.qrator.failoverbeacon.okhttp.fakes.PortsByAddress
import net.qrator.failoverbeacon.okhttp.fakes.Relay
import net.qrator.failoverbeacon.okhttp.fakes.isRetryControlSupported
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class TimeoutRetryTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private lateinit var relay: Relay
    private lateinit var client: OkHttpClient

    @BeforeTest
    fun setUp() {
        val config = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(
                listOf(
                    FailoverHost(
                        API,
                        additionalAddresses = listOf(FIRST_ADDRESS, SECOND_ADDRESS),
                        tcpProbe = false
                    )
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
        relay.close()
        server.shutdown()
        SdkRegistry.clear()
    }

    @Test
    fun `a GET on a dead HTTP2 connection is delivered through the other address on OkHttp 5_4`() {
        val result = callOnDeadConnection(Protocol.H2_PRIOR_KNOWLEDGE)

        val expected = if (isRetryControlSupported) Result(true, 1) else Result(false, 0)
        assertEquals(expected, result)
    }

    @Test
    fun `the record marks addresses given while DNS fails and the SDK's resend`() {
        val exchanges = mutableListOf<Exchange>()
        val sdk = assertNotNull(SdkRegistry.instance)
        sdk.exchangeBus.subscribe(ExchangeSubscriber(exchanges::add))

        callOnDeadConnection(Protocol.H2_PRIOR_KNOWLEDGE)

        val expected = listOf(FailoverMark.DNS_FAILED, FailoverMark.ON) +
            if (isRetryControlSupported) listOf(FailoverMark.RETRY) else emptyList()
        assertEquals(expected, exchanges.map { it.failover })
    }

    @Test
    fun `an idempotent POST on a dead HTTP2 connection is delivered on OkHttp 5_4`() {
        val result = callOnDeadConnection(Protocol.H2_PRIOR_KNOWLEDGE) {
            post(PAYMENT).idempotent()
        }

        val expected = if (isRetryControlSupported) Result(true, 1) else Result(false, 0)
        assertEquals(expected, result)
    }

    @Test
    fun `a POST on a dead HTTP2 connection is not sent again`() {
        val result = callOnDeadConnection(Protocol.H2_PRIOR_KNOWLEDGE) { post(PAYMENT) }

        assertEquals(Result(isDelivered = false, requests = 0), result)
    }

    @Test
    fun `an idempotent POST with a one-shot body on a dead HTTP2 connection is not sent again`() {
        val result = callOnDeadConnection(Protocol.H2_PRIOR_KNOWLEDGE) {
            post(ONE_SHOT_PAYMENT).idempotent()
        }

        assertEquals(Result(isDelivered = false, requests = 0), result)
    }

    @Test
    fun `a call timeout during the wait for the ping stops the retry`() {
        val result = callOnDeadConnection(
            Protocol.H2_PRIOR_KNOWLEDGE,
            configureClient = { callTimeout(1, TimeUnit.SECONDS) }
        )

        assertEquals(Result(isDelivered = false, requests = 0), result)
    }

    @Test
    fun `a dead HTTP1 connection is not retried and the next call uses the other address`() {
        val result = callOnDeadConnection(Protocol.HTTP_1_1)
        val next = execute(client, Request.Builder().url(url()))

        assertEquals(Result(isDelivered = false, requests = 0), result)
        assertEquals("ok", next)
        assertEquals(1, relay.connectionCount, "the next call did not use the first address")
    }

    @Test
    fun `the SDK does not retry a timeout when the client turned retries off`() {
        val result = callOnDeadConnection(
            Protocol.H2_PRIOR_KNOWLEDGE,
            configureClient = { retryOnConnectionFailure(false) }
        )

        assertEquals(Result(isDelivered = false, requests = 0), result)
    }

    @Test
    fun `a slow server on a live HTTP2 connection gets the request once`() {
        val client = start(Protocol.H2_PRIOR_KNOWLEDGE)
        warmUp(client)
        server.enqueue(MockResponse().setBody("late").setHeadersDelay(2, TimeUnit.SECONDS))
        val before = server.requestCount

        val failure = runCatching { execute(client, Request.Builder().url(url())) }

        assertIs<SocketTimeoutException>(failure.exceptionOrNull())
        assertEquals(1, server.requestCount - before)
    }

    private fun callOnDeadConnection(
        protocol: Protocol,
        configureClient: OkHttpClient.Builder.() -> Unit = {},
        customize: Request.Builder.() -> Unit = {}
    ): Result {
        val client = start(protocol, configureClient)
        warmUp(client)
        relay.isBlackHole = true
        server.enqueue(MockResponse().setBody("ok"))
        val before = server.requestCount
        val isDelivered = try {
            execute(client, Request.Builder().url(url()).apply(customize))
            true
        } catch (_: IOException) {
            false
        }
        return Result(isDelivered, server.requestCount - before)
    }

    private fun start(
        protocol: Protocol,
        configureClient: OkHttpClient.Builder.() -> Unit = {}
    ): OkHttpClient {
        server.protocols = listOf(protocol)
        server.start(LOOPBACK, 0)
        relay = Relay(server.port)
        client = OkHttpClient.Builder()
            .protocols(listOf(protocol))
            .socketFactory(PortsByAddress(mapOf(FIRST to relay.port, SECOND to server.port)))
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .apply(configureClient)
            .build()
            .withFailoverBeacon()
        return client
    }

    private fun warmUp(client: OkHttpClient) {
        server.enqueue(MockResponse().setBody("warm"))
        assertEquals("warm", execute(client, Request.Builder().url(url())))
        assertEquals(1, relay.connectionCount, "the warm-up used the first address")
    }

    private fun execute(client: OkHttpClient, request: Request.Builder): String =
        client.newCall(request.build()).execute().use { it.body.string() }

    private fun url() = server.url("/").newBuilder().host(API).build()

    private data class Result(val isDelivered: Boolean, val requests: Int)

    private companion object {
        const val API = "api.test"
        const val FIRST_ADDRESS = "192.0.2.2"
        const val SECOND_ADDRESS = "192.0.2.3"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        val FIRST: InetAddress = InetAddress.getByName(FIRST_ADDRESS)
        val SECOND: InetAddress = InetAddress.getByName(SECOND_ADDRESS)
        val PAYMENT = object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("payment")
            }
        }
        val ONE_SHOT_PAYMENT = object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("payment")
            }
        }
    }
}
