package net.qrator.failoverbeacon.okhttp

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.okhttp.fakes.RecordingEventListener
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconDnsFactory
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconEventListenerFactory
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconRetryInterceptor
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class WithFailoverBeaconTest {
    private val server = MockWebServer()

    @BeforeTest
    fun setUp() {
        installSdk({})
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.clear()
    }

    @Test
    fun `app listener receives the same events with and without the SDK`() {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        val withoutSdk = RecordingEventListener()
        val withSdk = RecordingEventListener()
        execute(clientWith(withoutSdk))
        execute(clientWith(withSdk).withFailoverBeacon())
        server.shutdown()

        assertFailsWith<IOException> { execute(clientWith(withoutSdk)) }
        assertFailsWith<IOException> { execute(clientWith(withSdk).withFailoverBeacon()) }

        assertEquals(withoutSdk.log, withSdk.log)
    }

    @Test
    fun `creates one app listener per call`() {
        repeat(3) { server.enqueue(MockResponse()) }
        val created = AtomicInteger()
        val client = OkHttpClient.Builder()
            .eventListenerFactory {
                created.incrementAndGet()
                EventListener.NONE
            }
            .build()
            .withFailoverBeacon()

        repeat(3) { execute(client) }

        assertEquals(3, created.get())
    }

    @Test
    fun `returns the same client when called on a returned client`() {
        val client = OkHttpClient().withFailoverBeacon()

        assertSame(client, client.withFailoverBeacon())
    }

    @Test
    fun `wraps a DNS or a listener set later on a copy of a returned client`() {
        val client = OkHttpClient().withFailoverBeacon()
        val appDns = Dns { listOf(InetAddress.getLoopbackAddress()) }

        val withDns = client.newBuilder().dns(appDns).build().withFailoverBeacon()
        val withListener =
            client.newBuilder().eventListener(EventListener.NONE).build().withFailoverBeacon()

        assertEquals(FailoverBeaconDnsFactory.create(appDns), withDns.dns)
        assertSame(client.eventListenerFactory, withDns.eventListenerFactory)
        assertTrue(withListener.eventListenerFactory is FailoverBeaconEventListenerFactory)
        assertSame(client.dns, withListener.dns)
        assertEquals(1, withDns.interceptors.count { it is FailoverBeaconRetryInterceptor })
    }

    @Test
    fun `copies of one client reuse each other's pooled connections`() {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        val original = OkHttpClient()

        execute(original.withFailoverBeacon())
        execute(original.withFailoverBeacon())

        server.takeRequest()
        assertEquals(1, server.takeRequest().sequenceNumber)
    }

    private fun clientWith(listener: EventListener): OkHttpClient =
        OkHttpClient.Builder().eventListenerFactory { listener }.build()

    private fun execute(client: OkHttpClient) {
        val request = Request.Builder().url(server.url("/")).build()
        client.newCall(request).execute().use { it.body.string() }
    }
}
