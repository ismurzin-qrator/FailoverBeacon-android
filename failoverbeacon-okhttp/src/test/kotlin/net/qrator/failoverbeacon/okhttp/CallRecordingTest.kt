package net.qrator.failoverbeacon.okhttp

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import okhttp3.Cache
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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
        installSdk(ExchangeSubscriber(exchanges::add))
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
        assertEquals(server.port, exchange.request.serverPort)
        assertEquals("GET", exchange.request.method)
        assertEquals(200, exchange.response?.statusCode)
        assertEquals(5L, exchange.response?.bodyBytes)
        assertEquals(LOOPBACK_IP, exchange.serverIp)
        assertEquals("http/1.1", exchange.protocol)
        assertEquals(false, exchange.reusedConnection)
        assertEquals(ConnectResult.Connected, exchange.attempts.single().result)
        assertNotNull(exchange.timings.connectNanos)
        assertNotNull(exchange.timings.waitNanos)
        assertNotNull(exchange.timings.receiveNanos)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
    }

    @Test
    fun `records the size of a request body`() {
        server.enqueue(MockResponse().setBody("ok"))
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = 5

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("hello")
            }
        }
        val request = Request.Builder().url(server.url("/")).post(body).build()

        client().build().withFailoverBeacon().newCall(request).execute().use { it.body.string() }

        val exchange = exchanges.single()
        assertEquals("POST", exchange.request.method)
        assertEquals(5L, exchange.request.bodyBytes)
        assertNotNull(exchange.timings.sendNanos)
    }

    @Test
    fun `records a chunked response without a content length`() {
        server.enqueue(MockResponse().setChunkedBody("hello world", 4))

        assertEquals("hello world", execute(client().build()))

        val response = exchanges.single().response
        assertNull(response?.contentLength)
        assertEquals(11L, response?.bodyBytes)
    }

    @Test
    fun `a second call reuses the pooled connection`() {
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("second"))
        val client = client().build()

        execute(client)
        execute(client)

        val second = exchanges[1]
        assertTrue(second.reusedConnection)
        assertTrue(second.attempts.isEmpty())
        assertEquals(LOOPBACK_IP, second.serverIp)
    }

    @Test
    fun `a redirect on the same connection is a second exchange`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        server.enqueue(MockResponse().setBody("next"))

        execute(client().build())

        val (redirect, followUp) = exchanges
        assertEquals(302, redirect.response?.statusCode)
        assertEquals(1, followUp.resendCount)
        assertEquals(200, followUp.response?.statusCode)
        assertTrue(followUp.reusedConnection)
        assertEquals(LOOPBACK_IP, followUp.serverIp)
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
    fun `a redirect to a host that does not resolve fails at DNS with an unknown target`() {
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
        assertNull(followUp.request.serverAddress)
        assertEquals(ExchangeStep.DNS, assertIs<ExchangeOutcome.Failed>(followUp.outcome).step)
    }

    @Test
    fun `an authentication challenge is a second exchange`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("secret"))
        val client = client()
            .authenticator { _, response ->
                response.request.newBuilder().header("Authorization", "Basic dGVzdA==").build()
            }
            .build()

        execute(client)

        assertEquals(listOf(401, 200), exchanges.map { it.response?.statusCode })
    }

    @Test
    fun `a refused connect fails at TCP`() {
        val closedPort = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        val request = Request.Builder().url("http://localhost:$closedPort/").build()

        assertFailsWith<IOException> {
            client().build().withFailoverBeacon().newCall(request).execute()
        }

        val exchange = exchanges.single()
        val attempt = assertIs<ConnectResult.Failed>(exchange.attempts.single().result)
        assertEquals(ExchangeStep.TCP, attempt.step)
        assertEquals(ExchangeStep.TCP, assertIs<ExchangeOutcome.Failed>(exchange.outcome).step)
    }

    @Test
    fun `a silent server fails at RESPONSE after the read timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = client().readTimeout(200, TimeUnit.MILLISECONDS).build()

        assertFailsWith<SocketTimeoutException> { execute(client) }

        val exchange = exchanges.single()
        val outcome = assertIs<ExchangeOutcome.Failed>(exchange.outcome)
        assertEquals(ExchangeStep.RESPONSE, outcome.step)
        assertIs<SocketTimeoutException>(outcome.cause)
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
        assertNull(exchange.response?.bodyBytes)
        assertEquals(ExchangeStep.RESPONSE, assertIs<ExchangeOutcome.Failed>(exchange.outcome).step)
    }

    @Test
    fun `a call timeout is a failure`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = client().callTimeout(200, TimeUnit.MILLISECONDS).build()

        assertFailsWith<InterruptedIOException> { execute(client) }

        assertIs<InterruptedIOException>(
            assertIs<ExchangeOutcome.Failed>(exchanges.single().outcome).cause
        )
    }

    @Test
    fun `a call canceled by the app is canceled`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = client().build().withFailoverBeacon()
            .newCall(Request.Builder().url(server.url("/")).build())
        val canceler = thread {
            Thread.sleep(200)
            call.cancel()
        }

        assertFailsWith<IOException> { call.execute() }
        canceler.join()

        assertEquals(ExchangeOutcome.Canceled, exchanges.single().outcome)
    }

    @Test
    fun `a response from the cache makes no exchange`() {
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=60").setBody("cached"))
        val cacheDir = Files.createTempDirectory("okhttp-cache").toFile()
        val client = client().cache(Cache(cacheDir, 1024 * 1024)).build()

        execute(client)
        execute(client)

        assertEquals(1, exchanges.size)
        cacheDir.deleteRecursively()
    }

    @Test
    fun `an HTTPS call records the handshake`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("secure"))
        val client = client()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .build()

        execute(client, "https://localhost:${server.port}/")

        val exchange = exchanges.single()
        assertNotNull(exchange.timings.tlsNanos)
        assertEquals(ConnectResult.Connected, exchange.attempts.single().result)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
    }

    @Test
    fun `an untrusted certificate fails at TLS`() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)

        assertFailsWith<SSLHandshakeException> {
            execute(client().build(), "https://localhost:${server.port}/")
        }

        val exchange = exchanges.single()
        assertEquals(
            ExchangeStep.TLS,
            assertIs<ConnectResult.Failed>(exchange.attempts.single().result).step
        )
        assertEquals(ExchangeStep.TLS, assertIs<ExchangeOutcome.Failed>(exchange.outcome).step)
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
            assertIs<SocketTimeoutException>(failed.cause)
        }
    }

    private fun client(): OkHttpClient.Builder = OkHttpClient.Builder().dns {
        listOf(LOOPBACK)
    }

    private fun execute(client: OkHttpClient, url: String = server.url("/").toString()): String {
        val request = Request.Builder().url(url).build()
        return client.withFailoverBeacon().newCall(request).execute().use { it.body.string() }
    }

    /** OkHttp 5 races connects to several addresses; 3.14 and 4.x try them one by one. */
    private fun hasFastFallback(): Boolean =
        OkHttpClient.Builder::class.java.methods.any { it.name == "fastFallback" }

    /** Connects to [SILENT] never succeed: they wait until the socket is closed or time out. */
    private class SilentFirstSocketFactory : SocketFactory() {
        override fun createSocket(): Socket = SilentFirstSocket()

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

    private class SilentFirstSocket : Socket() {
        private val closed = CountDownLatch(1)

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if ((endpoint as InetSocketAddress).address != SILENT) {
                super.connect(endpoint, timeout)
                return
            }
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
        val LOOPBACK: InetAddress = InetAddress.getByName(LOOPBACK_IP)
        val SILENT: InetAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))

        val certificate: HeldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
    }
}
