package net.qrator.failoverbeacon.okhttp

import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class FailoverEndToEndTest {
    private val app = RuntimeEnvironment.getApplication()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val active = assertNotNull(connectivity.activeNetwork)
    private val server = MockWebServer()
    private val sdkLookups = CopyOnWriteArrayList<String>()
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
                    FailoverHost(RANKED, server.port, listOf(UNREACHABLE, "127.0.0.1")),
                    FailoverHost(RESOLVED),
                    FailoverHost(UNUSED),
                    FailoverHost(
                        UNPROBED,
                        additionalAddresses = listOf(FIRST_ADDRESS, SECOND_ADDRESS),
                        tcpProbe = false
                    )
                )
            )
            .dnsResolver { sdkDns(it) }
            .build()
        shadowOf(connectivity).apply {
            setNetworkCapabilities(active, ShadowNetworkCapabilities.newInstance())
            setLinkProperties(active, LinkProperties())
        }
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
    fun `a configured host is resolved through the configured DNS`() {
        server.enqueue(MockResponse().setBody("ok"))

        assertEquals("ok", execute(RESOLVED))
    }

    @Test
    fun `a new connection starts with the address that answered the probe`() {
        val connects = CopyOnWriteArrayList<InetAddress>()
        val client = OkHttpClient.Builder()
            .dns { appDns(it) }
            .eventListener(ConnectStarts(connects))
            .build()
            .withFailoverBeacon()
        server.enqueue(MockResponse().setBody("ok"))
        assertTrue(awaitLeader(RANKED, LOOPBACK), "probe of $RANKED did not finish")

        assertEquals("ok", execute(RANKED, client))
        assertEquals(LOOPBACK, connects.first())
    }

    @Test
    fun `a request on a pooled connection refreshes a host the app does not call`() {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals("ok", execute(RESOLVED))
        assertTrue(awaitIdleResolvers(), "resolvers did not finish")
        val lookupsBefore = sdkLookups.count { it == UNUSED }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))

        assertEquals("ok", execute(RESOLVED))

        server.takeRequest()
        assertEquals(1, server.takeRequest().sequenceNumber, "the request used a new connection")
        assertTrue(
            await { sdkLookups.count { it == UNUSED } > lookupsBefore },
            "$UNUSED was not refreshed"
        )
    }

    @Test
    fun `after a failed connect the next new connection starts with the other address`() {
        val closedPort = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        val connects = CopyOnWriteArrayList<InetAddress>()
        val client = OkHttpClient.Builder()
            .socketFactory(
                PortsByAddress(mapOf(FIRST to closedPort, SECOND to server.port))
            )
            .eventListener(ConnectStarts(connects))
            .build()
            .withFailoverBeacon()
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))

        assertEquals("ok", execute(UNPROBED, client))
        val first = connects.toList()
        connects.clear()
        client.connectionPool.evictAll()
        assertEquals("ok", execute(UNPROBED, client))

        assertEquals(FIRST, first.first())
        assertEquals(SECOND, connects.first())
    }

    @Test
    fun `after a failed handshake the next new connection starts with the other address`() {
        val secure = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("ok"))
            enqueue(MockResponse().setBody("ok"))
            start(LOOPBACK, 0)
        }
        val closesAtOnce = ServerSocket(0, 8, LOOPBACK)
        thread(isDaemon = true) {
            runCatching { while (true) closesAtOnce.accept().close() }
        }
        val connects = CopyOnWriteArrayList<InetAddress>()
        val client = OkHttpClient.Builder()
            .socketFactory(
                PortsByAddress(mapOf(FIRST to closesAtOnce.localPort, SECOND to secure.port))
            )
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .eventListener(ConnectStarts(connects))
            .build()
            .withFailoverBeacon()

        try {
            assertEquals("ok", execute(UNPROBED, client, secure))
            val first = connects.toList()
            connects.clear()
            client.connectionPool.evictAll()
            assertEquals("ok", execute(UNPROBED, client, secure))

            assertEquals(FIRST, first.first())
            assertEquals(SECOND, connects.first())
        } finally {
            closesAtOnce.close()
            secure.shutdown()
        }
    }

    @Test
    fun `other hosts keep the app's DNS answer and error`() {
        server.enqueue(MockResponse().setBody("ok"))

        assertEquals("ok", execute(OTHER))
        assertFailsWith<UnknownHostException> { execute("missing.test") }
    }

    @Test
    fun `without a network a configured host gets the app's DNS error`() {
        shadowOf(connectivity).networkCallbacks.single().onLost(active)

        assertFailsWith<UnknownHostException> { execute(API) }
    }

    @Test
    fun `after stop a configured host gets the app's DNS error`() {
        FailoverBeacon.stop()

        assertFailsWith<UnknownHostException> { execute(API) }
    }

    private fun appDns(host: String): List<InetAddress> =
        if (host == OTHER) listOf(LOOPBACK) else throw UnknownHostException(host)

    private fun sdkDns(host: String): List<InetAddress> {
        sdkLookups += host
        return if (host == RESOLVED) listOf(LOOPBACK) else throw UnknownHostException(host)
    }

    private fun awaitIdleResolvers(): Boolean = await {
        val threads = assertNotNull(SdkRegistry.instance).threads.failoverResolve
        threads.activeCount == 0 && threads.queue.isEmpty()
    }

    private fun await(condition: () -> Boolean): Boolean {
        repeat(500) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun awaitLeader(host: String, address: InetAddress): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (SdkRegistry.instance?.dns?.lookup(host)?.first() == address) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun execute(
        host: String,
        client: OkHttpClient = this.client,
        server: MockWebServer = this.server
    ): String {
        val url = server.url("/").newBuilder().host(host).build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
    }

    private class ConnectStarts(private val connects: MutableList<InetAddress>) : EventListener() {
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects += inetSocketAddress.address
        }
    }

    private class PortsByAddress(private val ports: Map<InetAddress, Int>) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val port = ports.getValue((endpoint as InetSocketAddress).address)
                super.connect(InetSocketAddress(LOOPBACK, port), timeout)
            }
        }

        override fun createSocket(host: String, port: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress, port: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = throw UnsupportedOperationException()
    }

    private companion object {
        const val API = "api.test"
        const val OTHER = "other.test"
        const val RANKED = "ranked.test"
        const val RESOLVED = "resolved.test"
        const val UNUSED = "unused.test"
        const val UNPROBED = "unprobed.test"
        const val UNREACHABLE = "192.0.2.1"
        const val FIRST_ADDRESS = "192.0.2.2"
        const val SECOND_ADDRESS = "192.0.2.3"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        val FIRST: InetAddress = InetAddress.getByName(FIRST_ADDRESS)
        val SECOND: InetAddress = InetAddress.getByName(SECOND_ADDRESS)

        val certificate: HeldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(UNPROBED)
            .build()
        val serverCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
    }
}
