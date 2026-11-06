package net.qrator.failoverbeacon.failover

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.failover.model.BodyProbeConfig
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FailoverTest {
    @Volatile
    private var now = START_MILLIS
    private var networkHandle = 0L
    private val resolutions = CopyOnWriteArrayList<String>()

    @Volatile
    private var isDnsAnswering = true
    private val certificate = certificateFor(HOST)
    private var server = ProbeServer(certificate)
    private val sdk = SdkInstance(
        appContext = RuntimeEnvironment.getApplication(),
        consent = TrackingConsent.GRANTED,
        clock = { System.nanoTime() },
        epochClock = { now },
        exchangeBus = ExchangeBus(),
        networkSource = { NetworkSnapshot.Unknown },
        foregroundSource = { null }
    )

    @AfterTest
    fun tearDown() {
        sdk.close()
        server.close()
    }

    @Test
    fun `a network change, a lookup and an exchange start a TCP round and DNS of stale hosts`() {
        // An answer would start a TCP round of its own.
        isDnsAnswering = false
        start()
        awaitUntil { server.tcpProbes.get() == 1 && server.bodyProbes.get() == 1 }
        awaitIdle()

        now += REFRESH_MILLIS
        changeNetwork()
        awaitUntil { server.tcpProbes.get() == 2 && resolutions.size == 2 }
        now += REFRESH_MILLIS
        lookup()
        awaitUntil { server.tcpProbes.get() == 3 && resolutions.size >= 3 }
        val resolvedBeforeExchange = resolutions.size
        now += REFRESH_MILLIS
        sdk.exchangeBus.publish(OTHER_HOST_EXCHANGE)
        awaitUntil { server.tcpProbes.get() == 4 && resolutions.size > resolvedBeforeExchange }
        awaitIdle()

        assertEquals(1, server.bodyProbes.get())
    }

    @Test
    fun `a network change and an exchange start a stale body probe, a lookup does not`() {
        start(refreshIntervalMillis = TimeUnit.DAYS.toMillis(1))
        awaitUntil { server.bodyProbes.get() == 1 && resolutions.size == 1 }
        awaitIdle()

        now += BODY_PROBE_MILLIS
        lookup()
        awaitUntil { resolutions.size == 2 }
        awaitIdle()
        val bodyProbesAfterLookup = server.bodyProbes.get()
        sdk.exchangeBus.publish(OTHER_HOST_EXCHANGE)
        awaitUntil { server.bodyProbes.get() == 2 }
        awaitIdle()
        now += BODY_PROBE_MILLIS
        changeNetwork()
        awaitUntil { server.bodyProbes.get() == 3 }

        assertEquals(1, bodyProbesAfterLookup)
        assertEquals(1, server.tcpProbes.get(), "fresh TCP results were probed again")
        assertEquals(2, resolutions.size, "a fresh answer was resolved again")
    }

    @Test
    fun `a body probe requests its path with the platform's trust and hostname verifier`() {
        withPlatformTls(trusting(certificate), { host, _ -> host == HOST }) {
            start(bodyProbe = BodyProbeConfig("/probe.bin", 2_048))

            awaitUntil { server.requests.isNotEmpty() }
        }

        assertTrue(server.requests.single().startsWith("GET /probe.bin HTTP/1.1\r\n"))
    }

    @Test
    fun `a name the default verifier rejects stops the body probe and moves the address last`() {
        val otherName = certificateFor("other.test")
        server.close()
        server = ProbeServer(otherName)

        withPlatformTls(trusting(otherName), HttpsURLConnection.getDefaultHostnameVerifier()) {
            assertProbeStopsAndLeaderGoesLast()
        }
    }

    @Test
    fun `an untrusted default SSL context stops the body probe and moves the address last`() {
        withPlatformTls(SSLContext.getDefault(), { host, _ -> host == HOST }) {
            assertProbeStopsAndLeaderGoesLast()
        }
    }

    @Test
    fun `stop closes a probe socket long before the probe timeout`() {
        val isLeftByClient = LinkedBlockingQueue<Boolean>()
        server.onBodyProbe = { socket -> isLeftByClient += socket.awaitClose() }
        start()
        awaitUntil { server.bodyProbes.get() == 1 }

        sdk.close()

        assertEquals(true, isLeftByClient.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    // Both addresses reach the server: the leader fails its probe and the other one is next.
    private fun assertProbeStopsAndLeaderGoesLast() {
        val arrived = LinkedBlockingQueue<Unit>()
        val proceed = Semaphore(0)
        server.onBodyProbe = {
            arrived += Unit
            proceed.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        start(otherAddress = ANY_LOCAL)
        assertNotNull(arrived.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the leader was not probed")
        val ranked = lookup()

        proceed.release()

        assertNotNull(arrived.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the next was not probed")
        assertEquals(ranked.reversed(), lookup())
        assertEquals(emptyList(), server.requests)
    }

    private fun start(
        otherAddress: InetAddress = NO_ROUTE,
        refreshIntervalMillis: Long = REFRESH_MILLIS,
        bodyProbe: BodyProbeConfig = BodyProbeConfig("/", 1_024)
    ) {
        val addresses = listOf(LOOPBACK, otherAddress)
        val host = FailoverHostConfig(HOST, server.port, addresses, tcpProbe = true, bodyProbe)
        val settings = FailoverSettings(
            preresolveOnNetworkChange = true,
            probeOnNetworkChange = true,
            refreshIntervalMillis = refreshIntervalMillis,
            fallbackWithoutEch = true
        )
        Failover(sdk, listOf(host), ::resolve, settings).apply {
            subscribe()
            start()
        }
        changeNetwork()
    }

    private fun resolve(host: String): List<InetAddress> {
        resolutions += host
        if (!isDnsAnswering) throw UnknownHostException(host)
        return listOf(LOOPBACK)
    }

    private fun changeNetwork() {
        sdk.publishNetwork(DefaultNetwork(++networkHandle, "WIFI/192.0.2", blocked = false))
        sdk.threads.storage.submit {}.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun lookup(): List<InetAddress> = assertNotNull(assertNotNull(sdk.dns).lookup(HOST))

    private fun awaitIdle() {
        awaitUntil { sdk.threads.failoverResolve.isIdle() }
        awaitUntil { sdk.threads.failoverProbe.isIdle() }
    }

    private fun ThreadPoolExecutor.isIdle() = completedTaskCount == taskCount

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition was not met in time" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun withPlatformTls(
        context: SSLContext,
        verifier: HostnameVerifier,
        block: () -> Unit
    ) {
        val defaultContext = SSLContext.getDefault()
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        try {
            SSLContext.setDefault(context)
            HttpsURLConnection.setDefaultHostnameVerifier(verifier)
            block()
        } finally {
            SSLContext.setDefault(defaultContext)
            HttpsURLConnection.setDefaultHostnameVerifier(defaultVerifier)
        }
    }

    /**
     * Counts TCP probes, which connect and close, and body probes, which start TLS. A body probe
     * goes on to a TLS server that records its request.
     */
    private class ProbeServer(certificate: HeldCertificate) : Closeable {
        private val plainServer = ServerSocket(0, BACKLOG, LOOPBACK)
        private val tlsServer = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
            .sslContext()
            .serverSocketFactory
            .createServerSocket(0, BACKLOG, LOOPBACK)
        val port: Int get() = plainServer.localPort
        val tcpProbes = AtomicInteger()
        val bodyProbes = AtomicInteger()
        val requests = CopyOnWriteArrayList<String>()

        @Volatile
        var onBodyProbe: (Socket) -> Unit = {}

        init {
            serveEach(plainServer, ::servePlain)
            serveEach(tlsServer, ::serveTls)
        }

        override fun close() {
            plainServer.close()
            tlsServer.close()
        }

        private fun servePlain(client: Socket) {
            val first = client.getInputStream().read()
            if (first < 0) {
                tcpProbes.incrementAndGet()
                return
            }
            bodyProbes.incrementAndGet()
            onBodyProbe(client)
            Socket(LOOPBACK, tlsServer.localPort).use { tls ->
                tls.getOutputStream().write(first)
                thread(isDaemon = true) {
                    runCatching { client.getInputStream().copyTo(tls.getOutputStream()) }
                    runCatching { tls.shutdownOutput() }
                }
                tls.getInputStream().copyTo(client.getOutputStream())
            }
        }

        private fun serveTls(socket: Socket) {
            val head = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                .lineSequence()
                .takeWhile { it.isNotEmpty() }
                .joinToString("") { "$it\r\n" }
            if (head.isEmpty()) return
            requests += head
            socket.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
            )
        }

        private fun serveEach(server: ServerSocket, serve: (Socket) -> Unit) =
            thread(isDaemon = true) {
                while (true) {
                    val accepted = runCatching { server.accept() }.getOrNull() ?: break
                    accepted.soTimeout = SERVER_TIMEOUT_MILLIS
                    thread(isDaemon = true) { accepted.use { runCatching { serve(it) } } }
                }
            }
    }

    private companion object {
        const val HOST = "probed.test"
        const val START_MILLIS = 1_794_000_000_000L
        val REFRESH_MILLIS = TimeUnit.MINUTES.toMillis(10)
        val BODY_PROBE_MILLIS = TimeUnit.HOURS.toMillis(1)
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 10L
        const val BACKLOG = 50

        // Below the 5 s timeout of a probe socket.
        const val SERVER_TIMEOUT_MILLIS = 3_000
        val LOOPBACK: InetAddress = InetAddress.getByAddress(HOST, byteArrayOf(127, 0, 0, 1))

        // Connects to the local host, so the server gets a second address of the host.
        val ANY_LOCAL: InetAddress = InetAddress.getByAddress(HOST, byteArrayOf(0, 0, 0, 0))

        // A TCP connect to a multicast address fails at once.
        val NO_ROUTE: InetAddress =
            InetAddress.getByAddress(HOST, byteArrayOf(224.toByte(), 0, 0, 1))

        val OTHER_HOST_EXCHANGE = Exchange(
            callId = 1,
            request = ExchangeRequest("https", "other.test", 443, "GET", bodyBytes = null),
            response = null,
            resendCount = 0,
            connection = null,
            reusedConnection = false,
            dnsAddresses = null,
            attempts = emptyList(),
            timings = ExchangeTimings(null, null, null, null, null, null),
            startTimeMillis = 0,
            durationNanos = 0,
            outcome = ExchangeOutcome.Ok,
            network = NetworkSnapshot.Unknown,
            foreground = null
        )

        fun certificateFor(host: String): HeldCertificate =
            HeldCertificate.Builder().addSubjectAlternativeName(host).build()

        fun trusting(certificate: HeldCertificate): SSLContext = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
            .sslContext()

        fun Socket.awaitClose(): Boolean = try {
            while (getInputStream().read() >= 0) continue
            true
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }
}
