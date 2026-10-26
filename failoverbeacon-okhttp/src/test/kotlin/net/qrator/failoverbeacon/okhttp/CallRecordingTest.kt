package net.qrator.failoverbeacon.okhttp

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.BufferedSink

/** Real calls through every OkHttp version of the test matrix. */
class CallRecordingTest {
    private val server = MockWebServer()
    private val exchanges = CopyOnWriteArrayList<Exchange>()

    @BeforeTest
    fun setUp() {
        server.start(LOOPBACK, 0)
        installSdk(
            ExchangeSubscriber(exchanges::add),
            network = NETWORK,
            foreground = true,
            startTimeMillis = START_TIME_MILLIS
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.clear()
    }

    @Test
    fun `records a call`() {
        server.enqueue(MockResponse().setBody("hello"))

        assertEquals("hello", execute(client().build()))

        val exchange = exchanges.single()
        assertEquals("http", exchange.request.scheme)
        assertEquals(server.port, exchange.request.serverPort)
        assertEquals("GET", exchange.request.method)
        assertEquals(200, exchange.response?.statusCode)
        assertEquals(5L, exchange.response?.contentLength)
        assertEquals(5L, exchange.response?.bodyBytes)
        assertEquals(LOOPBACK_IP, exchange.connection?.peerAddress)
        assertEquals("http/1.1", exchange.connection?.protocol)
        assertEquals(ProxyType.DIRECT, exchange.connection?.proxy)
        assertNull(exchange.connection?.tls)
        assertEquals(false, exchange.reusedConnection)
        assertEquals(listOf(LOOPBACK_IP), exchange.dnsAddresses)
        assertEquals(ConnectResult.Connected, exchange.attempts.single().result)
        assertEquals(ProxyType.DIRECT, exchange.attempts.single().proxy)
        assertNotNull(exchange.timings.connectNanos)
        assertNotNull(exchange.timings.waitNanos)
        assertNotNull(exchange.timings.receiveNanos)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
        assertEquals(NETWORK, exchange.network)
        assertEquals(true, exchange.foreground)
        assertEquals(START_TIME_MILLIS, exchange.startTimeMillis)
    }

    @Test
    fun `records an asynchronous call`() {
        server.enqueue(MockResponse().setBody("ok"))
        val done = CountDownLatch(1)
        val call = client().build().withFailoverBeacon()
            .newCall(Request.Builder().url(server.url("/")).build())

        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = done.countDown()

                override fun onResponse(call: Call, response: Response) {
                    response.use { it.body.string() }
                    done.countDown()
                }
            }
        )

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(ExchangeOutcome.Ok, exchanges.single().outcome)
    }

    @Test
    fun `a call through an HTTP proxy records the proxy and its address`() {
        server.enqueue(MockResponse().setBody("proxied"))
        // Like the system selector, names the proxy by host: OkHttp resolves it with the client's Dns.
        val proxyAddress = InetSocketAddress.createUnresolved("proxy.test", server.port)
        val client = client().proxy(Proxy(Proxy.Type.HTTP, proxyAddress)).build()

        assertEquals("proxied", execute(client, "http://origin.test/"))

        val exchange = exchanges.single()
        assertEquals("origin.test", exchange.request.serverAddress)
        assertEquals(LOOPBACK_IP, exchange.connection?.peerAddress)
        assertEquals(listOf(LOOPBACK_IP), exchange.dnsAddresses)
        assertEquals(ProxyType.HTTP, exchange.connection?.proxy)
        assertEquals(ProxyType.HTTP, exchange.attempts.single().proxy)
        assertEquals(false, exchange.reusedConnection)
    }

    @Test
    fun `records the size of a request body`() {
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url(server.url("/")).post(body(bytes = 5)).build()

        client().build().withFailoverBeacon().newCall(request).execute().use { it.body.string() }

        val exchange = exchanges.single()
        assertEquals("POST", exchange.request.method)
        assertEquals(5L, exchange.request.bodyBytes)
        assertNotNull(exchange.timings.sendNanos)
    }

    @Test
    fun `a second call reuses the pooled connection`() {
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("second"))
        val client = client().build()

        execute(client)
        execute(client)

        val (first, second) = exchanges
        assertNotEquals(first.callId, second.callId)
        assertTrue(second.reusedConnection)
        assertTrue(second.attempts.isEmpty())
        assertEquals(LOOPBACK_IP, second.connection?.peerAddress)
    }

    @Test
    fun `a redirect on the same connection is a second exchange`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        server.enqueue(MockResponse().setBody("next"))

        execute(client().build())

        val (redirect, followUp) = exchanges
        assertEquals(302, redirect.response?.statusCode)
        assertEquals(redirect.callId, followUp.callId)
        assertEquals(1, followUp.resendCount)
        assertEquals("GET", followUp.request.method)
        assertEquals(200, followUp.response?.statusCode)
        assertTrue(followUp.reusedConnection)
        assertEquals(LOOPBACK_IP, followUp.connection?.peerAddress)
        assertNull(followUp.dnsAddresses)
    }

    @Test
    fun `a redirect to another server has its own target`() {
        val other = MockWebServer()
        other.start(LOOPBACK, 0)
        other.enqueue(MockResponse().setBody("other"))
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", other.url("/").toString())
        )

        try {
            execute(client().build())
        } finally {
            other.shutdown()
        }

        val (redirect, followUp) = exchanges
        assertEquals(server.port, redirect.request.serverPort)
        assertEquals(other.port, followUp.request.serverPort)
        assertEquals(false, followUp.reusedConnection)
        assertEquals(ConnectResult.Connected, followUp.attempts.single().result)
    }

    @Test
    fun `a redirect to a host that does not resolve fails at DNS`() {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "http://nowhere.test/")
        )
        val client = client()
            .dns { host ->
                if (host == "nowhere.test") throw UnknownHostException(host)
                listOf(LOOPBACK)
            }
            .build()

        assertFailsWith<UnknownHostException> { execute(client) }

        val (redirect, followUp) = exchanges
        assertEquals(ExchangeOutcome.Ok, redirect.outcome)
        assertEquals(
            if (namesTargetEarly()) "nowhere.test" else null,
            followUp.request.serverAddress
        )
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.DNS, FailureType.NAME_NOT_RESOLVED),
            followUp.outcome
        )
    }

    @Test
    fun `a follow-up failing before its headers are sent is to the server of its connection`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = client()
            .authenticator { _, response ->
                response.request.newBuilder().header("X-Big", "x".repeat(LARGE_HEADER)).build()
            }
            .addNetworkInterceptor { chain ->
                if (chain.request().header("X-Big") != null) chain.call().cancel()
                chain.proceed(chain.request())
            }
            .build()

        // OkHttp 4.12.0 throws IllegalStateException here, with or without the SDK.
        assertFails { execute(client, "http://a.test:${server.port}/") }

        val (challenge, followUp) = exchanges
        assertEquals(401, challenge.response?.statusCode)
        assertEquals("http", followUp.request.scheme)
        assertEquals("a.test", followUp.request.serverAddress)
        assertEquals(server.port, followUp.request.serverPort)
        assertTrue(followUp.reusedConnection)
    }

    @Test
    fun `a refused connect fails at TCP`() {
        val closedPort = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        val request = Request.Builder().url(
            "http://localhost:$closedPort/"
        ).method("DELETE", null).build()

        // OkHttp 5 resends the request, 3.14 and 4.x fail the call.
        runCatching { client().build().withFailoverBeacon().newCall(request).execute().close() }

        val exchange = exchanges.first()
        assertEquals("http", exchange.request.scheme)
        assertEquals("DELETE", exchange.request.method)
        val refused = ConnectResult.Failed(ExchangeStep.TCP, FailureType.REFUSED)
        assertEquals(refused, exchange.attempts.single().result)
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.TCP, FailureType.REFUSED),
            exchange.outcome
        )
    }

    @Test
    fun `a silent server fails at RESPONSE after the read timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = client().readTimeout(200, TimeUnit.MILLISECONDS).build()

        assertFailsWith<SocketTimeoutException> { execute(client) }

        val exchange = exchanges.single()
        val outcome = assertIs<ExchangeOutcome.Failed>(exchange.outcome)
        assertEquals(ExchangeStep.RESPONSE, outcome.step)
        assertEquals(FailureType.TIMED_OUT, outcome.type)
        assertNotNull(exchange.timings.waitNanos)
    }

    @Test
    fun `a cut body fails at RESPONSE and keeps the status`() {
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(64 * 1024))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        assertFailsWith<IOException> { execute(client().build()) }

        val exchange = exchanges.single()
        assertEquals(200, exchange.response?.statusCode)
        assertEquals(64L * 1024, exchange.response?.contentLength)
        assertNull(exchange.response?.bodyBytes)
        assertEquals(ExchangeStep.RESPONSE, assertIs<ExchangeOutcome.Failed>(exchange.outcome).step)
    }

    @Test
    fun `too many redirects fail the last exchange as a redirect loop`() {
        repeat(MAX_FOLLOW_UPS + 1) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/"))
        }

        assertFailsWith<ProtocolException> { execute(client().build()) }

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.RESPONSE, FailureType.REDIRECT_LOOP),
            exchanges.last().outcome
        )
    }

    @Test
    fun `a call timeout fails as timed out`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = client().callTimeout(200, TimeUnit.MILLISECONDS).build()

        assertFailsWith<InterruptedIOException> { execute(client) }

        assertEquals(
            FailureType.TIMED_OUT,
            assertIs<ExchangeOutcome.Failed>(exchanges.single().outcome).type
        )
    }

    @Test
    fun `a call canceled by the app is canceled`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = client().build().withFailoverBeacon()
            .newCall(Request.Builder().url(server.url("/")).build())
        val canceler = thread {
            server.takeRequest(5, TimeUnit.SECONDS)
            call.cancel()
        }

        assertFailsWith<IOException> { call.execute() }
        canceler.join()

        assertEquals(ExchangeOutcome.Canceled(ExchangeStep.RESPONSE), exchanges.single().outcome)
    }

    @Test
    fun `a response from the cache makes no exchange`() {
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=60").setBody("cached"))
        val cacheDir = Files.createTempDirectory("okhttp-cache").toFile()
        val cache = Cache(cacheDir, 1024 * 1024)
        try {
            val client = client().cache(cache).build()

            execute(client)
            execute(client)

            assertEquals(1, exchanges.size)
        } finally {
            cache.close()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `HTTP 2 without TLS is recorded as h2`() {
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.enqueue(MockResponse().setBody("ok"))
        val client = client().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()

        execute(client)

        val exchange = exchanges.single()
        assertEquals("h2", exchange.connection?.protocol)
        assertEquals("http", exchange.request.scheme)
    }

    @Test
    fun `an HTTPS call records the handshake`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("secure"))
        val client = httpsClient()
            .build()

        execute(client, "https://localhost:${server.port}/")

        val exchange = exchanges.single()
        assertEquals("https", exchange.request.scheme)
        assertNotNull(exchange.timings.tlsNanos)
        assertEquals(ConnectResult.Connected, exchange.attempts.single().result)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
        val tls = assertNotNull(exchange.connection?.tls)
        assertTrue(tls.version.startsWith("TLSv"), tls.version)
        assertTrue(tls.cipher.startsWith("TLS_"), tls.cipher)
        assertEquals("CN=Test CA", tls.issuer)
    }

    @Test
    fun `a pooled HTTPS connection keeps its handshake`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("second"))
        val client = httpsClient()
            .build()

        execute(client, "https://localhost:${server.port}/")
        execute(client, "https://localhost:${server.port}/")

        val (first, second) = exchanges
        assertTrue(second.reusedConnection)
        assertNull(second.dnsAddresses)
        assertEquals(first.connection?.tls, second.connection?.tls)
        assertNotNull(second.connection?.tls)
    }

    @Test
    fun `a call coalesced onto another host's HTTP 2 connection is recorded under its own host`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        server.enqueue(MockResponse().setBody("a"))
        server.enqueue(MockResponse().setBody("b"))
        val client = httpsClient()
            .build()

        execute(client, "https://a.test:${server.port}/")
        execute(client, "https://b.test:${server.port}/")

        val (first, coalesced) = exchanges
        assertEquals("h2", first.connection?.protocol)
        assertEquals("b.test", coalesced.request.serverAddress)
        assertTrue(coalesced.reusedConnection)
        assertTrue(coalesced.attempts.isEmpty())
    }

    @Test
    fun `a redirect coalesced onto the connection of another host is recorded under its own`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        server.enqueue(
            MockResponse().setResponseCode(
                302
            ).setHeader("Location", "https://b.test:${server.port}/")
        )
        server.enqueue(MockResponse().setBody("b"))
        val client = httpsClient()
            .build()

        execute(client, "https://a.test:${server.port}/")

        val (redirect, followUp) = exchanges
        assertEquals("a.test", redirect.request.serverAddress)
        assertEquals("b.test", followUp.request.serverAddress)
    }

    @Test
    fun `a coalesced call failing before its headers keeps its own host`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        server.enqueue(MockResponse().setBody("a"))
        val client = httpsClient()
            .addNetworkInterceptor { chain ->
                if (chain.request().url.host == "b.test") throw IOException("no token")
                chain.proceed(chain.request())
            }
            .build()

        execute(client, "https://a.test:${server.port}/")
        assertFailsWith<IOException> { execute(client, "https://b.test:${server.port}/") }

        val coalesced = exchanges.last()
        assertTrue(coalesced.reusedConnection)
        assertEquals("b.test", coalesced.request.serverAddress)
    }

    @Test
    fun `the issuer is read without verifying the chain again`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("secure"))
        val trustManager = object : X509TrustManager by clientCertificates.trustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)
        val client = client().sslSocketFactory(sslContext.socketFactory, trustManager).build()

        execute(client, "https://localhost:${server.port}/")

        assertEquals("CN=Test CA", exchanges.single().connection?.tls?.issuer)
    }

    @Test
    fun `an app interceptor failing after the handshake fails at no network step`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val client = httpsClient()
            .addNetworkInterceptor { throw IOException("no token") }
            .build()

        assertFailsWith<IOException> { execute(client, "https://localhost:${server.port}/") }

        val exchange = exchanges.single()
        assertEquals(ConnectResult.Connected, exchange.attempts.single().result)
        assertEquals(ExchangeOutcome.Failed(null, FailureType.OTHER), exchange.outcome)
    }

    @Test
    fun `an untrusted certificate fails at TLS`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)

        assertFailsWith<SSLHandshakeException> {
            execute(client().build(), "https://localhost:${server.port}/")
        }

        val exchange = exchanges.single()
        val untrusted = ConnectResult.Failed(ExchangeStep.TLS, FailureType.CERT_UNTRUSTED)
        assertEquals(untrusted, exchange.attempts.single().result)
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.TLS, FailureType.CERT_UNTRUSTED),
            exchange.outcome
        )
    }

    @Test
    fun `a connect losing a race is superseded, a slow sequential one is a failure`() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = client()
            .dns { listOf(SILENT, LOOPBACK) }
            .socketFactory(SilentFirstSocketFactory())
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        execute(client)

        val (silent, working) = exchanges.single().attempts
        assertEquals("10.0.0.1", silent.peerAddress)
        assertEquals(ConnectResult.Connected, working.result)
        if (hasFastFallback()) {
            assertEquals(ConnectResult.Superseded, silent.result)
        } else {
            val failed = assertIs<ConnectResult.Failed>(silent.result)
            assertEquals(FailureType.TIMED_OUT, failed.type)
        }
    }

    @Test
    fun `a failed upload fails at REQUEST with no wait`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
        server.enqueue(MockResponse().setBody("ok"))
        val body = body(bytes = 4 * 1024 * 1024)
        val request = Request.Builder().url(server.url("/")).post(body).build()

        // OkHttp 5 resends the request, 3.14 and 4.x fail the call.
        runCatching { client().build().withFailoverBeacon().newCall(request).execute().close() }

        val exchange = exchanges.first()
        assertEquals(ExchangeStep.REQUEST, assertIs<ExchangeOutcome.Failed>(exchange.outcome).step)
        assertNull(exchange.timings.waitNanos)
    }

    @Test
    fun `a stale pooled connection is resent to the same server`() {
        server.enqueue(MockResponse().setBody("a").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(MockResponse().setBody("b"))
        val client = client().build()

        execute(client)
        assertEquals("b", execute(client))

        val (_, stale, resent) = exchanges
        assertTrue(stale.reusedConnection)
        assertIs<ExchangeOutcome.Failed>(stale.outcome)
        assertEquals(1, resent.resendCount)
        assertEquals(stale.request, resent.request)
        assertEquals(ExchangeOutcome.Ok, resent.outcome)
    }

    @Test
    fun `an app interceptor moving on to another server records both requests`() {
        server.enqueue(MockResponse().setBody("ok"))
        val closed = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        val client = client()
            .addInterceptor { chain ->
                val primary = chain.request().newBuilder().url("http://down.test:$closed/").build()
                try {
                    chain.proceed(primary)
                } catch (_: IOException) {
                    chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
                }
            }
            .build()

        assertEquals("ok", execute(client, "http://app.test/"))

        if (namesTargetEarly()) {
            val (primary, backup) = exchanges
            assertEquals("down.test", primary.request.serverAddress)
            assertEquals(ExchangeStep.TCP, assertIs<ExchangeOutcome.Failed>(primary.outcome).step)
            assertEquals(ExchangeOutcome.Ok, backup.outcome)
        } else {
            assertEquals(ExchangeOutcome.Ok, exchanges.single().outcome)
        }
    }

    @Test
    fun `a call canceled while connecting cancels the connect`() {
        val factory = SilentFirstSocketFactory()
        val client = client()
            .dns { listOf(SILENT) }
            .socketFactory(factory)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
        val call = client.withFailoverBeacon()
            .newCall(Request.Builder().url(server.url("/")).build())
        val canceler = thread {
            factory.silentConnect.await(5, TimeUnit.SECONDS)
            call.cancel()
        }

        assertFailsWith<IOException> { call.execute() }
        canceler.join()

        val exchange = exchanges.single()
        assertEquals(ConnectResult.Canceled, exchange.attempts.single().result)
        assertEquals(ExchangeOutcome.Canceled(ExchangeStep.TCP), exchange.outcome)
    }

    private fun client(): OkHttpClient.Builder = OkHttpClient.Builder().dns {
        listOf(LOOPBACK)
    }

    private fun execute(client: OkHttpClient, url: String = server.url("/").toString()): String {
        val request = Request.Builder().url(url).build()
        return client.withFailoverBeacon().newCall(request).execute().use { it.body.string() }
    }

    /** OkHttp 4.1+ names the server of a request before its DNS lookup, 3.14 only once it is sent. */
    private fun namesTargetEarly(): Boolean =
        EventListener::class.java.methods.any { it.name == "proxySelectStart" }

    /** OkHttp 5 races connects to several addresses; 3.14 and 4.x try them one by one. */
    private fun hasFastFallback(): Boolean =
        OkHttpClient.Builder::class.java.methods.any { it.name == "fastFallback" }

    /** Connects to [SILENT] never succeed: they wait until the socket is closed or time out. */
    private class SilentFirstSocketFactory : SocketFactory() {
        val silentConnect = CountDownLatch(1)

        override fun createSocket(): Socket = SilentFirstSocket(silentConnect)

        override fun createSocket(host: String, port: Int): Socket = unsupported()

        override fun createSocket(
            host: String,
            port: Int,
            local: InetAddress,
            localPort: Int
        ): Socket = unsupported()

        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()

        override fun createSocket(
            host: InetAddress,
            port: Int,
            local: InetAddress,
            localPort: Int
        ): Socket = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private fun httpsClient() = client()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)

    private fun body(bytes: Int) = object : RequestBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = bytes.toLong()

        override fun writeTo(sink: BufferedSink) {
            sink.write(ByteArray(bytes))
        }
    }

    private class SilentFirstSocket(private val silentConnect: CountDownLatch) : Socket() {
        private val closed = CountDownLatch(1)

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if ((endpoint as InetSocketAddress).address != SILENT) {
                super.connect(endpoint, timeout)
                return
            }
            silentConnect.countDown()
            // Closing the socket from another thread fails a real connect with a plain
            // SocketException. Timeout fails it with SocketTimeoutException.
            if (closed.await(timeout.toLong(), TimeUnit.MILLISECONDS)) {
                throw SocketException("Socket closed")
            }
            throw SocketTimeoutException("connect timed out")
        }

        @Synchronized
        override fun close() {
            closed.countDown()
            super.close()
        }
    }

    private companion object {
        const val LOOPBACK_IP = "127.0.0.1"
        val NETWORK = NetworkSnapshot.None
        const val START_TIME_MILLIS = 1_761_040_980_000
        const val MAX_FOLLOW_UPS = 20
        const val LARGE_HEADER = 64 * 1024
        val LOOPBACK: InetAddress = InetAddress.getByName(LOOPBACK_IP)
        val SILENT: InetAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))

        val root: HeldCertificate = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Test CA")
            .build()
        val certificate: HeldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("a.test")
            .addSubjectAlternativeName("b.test")
            .signedBy(root)
            .build()
        val serverCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate, root.certificate)
            .build()
        val clientCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(root.certificate)
            .build()
    }
}
