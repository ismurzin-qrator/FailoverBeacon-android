package net.qrator.failoverbeacon.okhttp

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.okhttp.fakes.RecordingEventListener
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
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

        assertEquals(withoutSdk.log, withSdk.log)
    }

    @Test
    fun `app listener receives the same events on a connection failure`() {
        server.start(0)
        server.shutdown()
        val withoutSdk = RecordingEventListener()
        val withSdk = RecordingEventListener()

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
    fun `leaves the original client unchanged`() {
        val factory = EventListener.Factory { EventListener.NONE }
        val original = OkHttpClient.Builder().eventListenerFactory(factory).build()

        val client = original.withFailoverBeacon()

        assertNotSame(original, client)
        assertSame(factory, original.eventListenerFactory)
    }

    @Test
    fun `shares the connection pool and dispatcher with the original client`() {
        val original = OkHttpClient()

        val client = original.withFailoverBeacon()

        assertSame(original.connectionPool, client.connectionPool)
        assertSame(original.dispatcher, client.dispatcher)
    }

    private fun clientWith(listener: EventListener): OkHttpClient =
        OkHttpClient.Builder().eventListenerFactory { listener }.build()

    private fun execute(client: OkHttpClient) {
        val request = Request.Builder().url(server.url("/")).build()
        client.newCall(request).execute().use { it.body.string() }
    }
}
