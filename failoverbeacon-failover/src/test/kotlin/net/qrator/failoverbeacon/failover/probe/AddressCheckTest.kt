package net.qrator.failoverbeacon.failover.probe

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.model.CheckOutcome
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddressCheckTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName(HOST).build()
    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()
    private val server = serverCertificates.sslContext().serverSocketFactory.createServerSocket(0)
    private val release = CountDownLatch(1)
    private val requests = ByteArrayOutputStream()
    private var isNameValid = true
    private var now = 0L
    private var clockStepNanos = 0L
    private val check = addressCheck(clientCertificates.sslSocketFactory())

    @AfterTest
    fun tearDown() {
        release.countDown()
        check.close()
        server.close()
    }

    @Test
    fun `reading up to the limit passes and sends the request`() {
        serve { socket -> socket.send(response("HTTP/1.1 200 OK", body = 4_096)) }

        assertEquals(CheckOutcome.PASSED, runCheck(path = "/check.bin", maxBytes = 1_024))

        val request = requests.toString(Charsets.US_ASCII.name())
        assertTrue(request.startsWith("GET /check.bin HTTP/1.1\r\n"), request)
        assertTrue("\r\nHost: $HOST:${server.localPort}\r\n" in request, request)
        assertTrue("\r\nRange: bytes=0-1023\r\n" in request, request)
    }

    @Test
    fun `a short response of any status passes when it ends`() {
        serve { socket -> socket.send(response("HTTP/1.1 404 Not Found", body = 10)) }

        assertEquals(CheckOutcome.PASSED, runCheck())
    }

    @Test
    fun `a response without a length passes when it ends`() {
        serve { socket -> socket.send("HTTP/1.1 200 OK\r\n\r\nbody".toByteArray()) }

        assertEquals(CheckOutcome.PASSED, runCheck())
    }

    @Test
    fun `an end before the declared length fails`() {
        serve { socket ->
            socket.send("HTTP/1.1 200 OK\r\ncontent-length: 100\r\n\r\nbody".toByteArray())
        }

        assertEquals(CheckOutcome.BODY_FAILED, runCheck())
    }

    @Test
    fun `an end without a response fails`() {
        serve { }

        assertEquals(CheckOutcome.BODY_FAILED, runCheck())
    }

    @Test
    fun `silence after part of a response fails`() {
        serve { socket ->
            socket.send("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nbody".toByteArray())
            release.await()
        }

        assertEquals(CheckOutcome.BODY_FAILED, runCheck())
    }

    @Test
    fun `a response past the deadline fails`() {
        clockStepNanos = TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS + 1)
        serve { socket -> socket.send(response("HTTP/1.1 200 OK", body = 10)) }

        assertEquals(CheckOutcome.BODY_FAILED, runCheck())
    }

    @Test
    fun `a closed port fails on TCP`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertEquals(CheckOutcome.TCP_FAILED, runCheck(port = closedPort))
    }

    @Test
    fun `an untrusted certificate fails on TLS`() {
        serve { }
        val otherCertificates = HandshakeCertificates.Builder().build()

        val outcome = addressCheck(otherCertificates.sslSocketFactory())
            .check(LOOPBACK, HOST, server.localPort, "/", MAX_BYTES)

        assertEquals(CheckOutcome.TLS_FAILED, outcome)
    }

    @Test
    fun `a foreign host name fails on TLS before the request`() {
        isNameValid = false
        serve { }

        assertEquals(CheckOutcome.TLS_FAILED, runCheck())
        assertEquals(0, requests.size())
    }

    @Test
    fun `close stops a read and later checks`() {
        val isReading = CountDownLatch(1)
        serve { socket ->
            isReading.countDown()
            release.await()
            socket.close()
        }
        thread {
            isReading.await()
            check.close()
        }

        assertEquals(CheckOutcome.BODY_FAILED, runCheck())
        assertEquals(CheckOutcome.TCP_FAILED, runCheck())
    }

    private fun addressCheck(socketFactory: SSLSocketFactory) = AddressCheck(
        clock = { now.also { now += clockStepNanos } },
        socketFactory = socketFactory,
        hostnameVerifier = { _, _ -> isNameValid },
        timeoutMillis = TIMEOUT_MILLIS,
        deadlineMillis = DEADLINE_MILLIS
    )

    private fun runCheck(
        port: Int = server.localPort,
        path: String = "/",
        maxBytes: Int = MAX_BYTES
    ): CheckOutcome = check.check(LOOPBACK, HOST, port, path, maxBytes)

    private fun serve(respond: (Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    thread(isDaemon = true) {
                        socket.use {
                            runCatching {
                                it.readRequest()
                                respond(it)
                            }
                        }
                    }
                } catch (_: IOException) {
                    return@thread
                }
            }
        }
    }

    private fun Socket.readRequest() {
        val head = ByteArrayOutputStream()
        while (!head.toString(Charsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
            val byte = getInputStream().read()
            if (byte < 0) return
            head.write(byte)
        }
        synchronized(requests) { requests.write(head.toByteArray()) }
    }

    private fun Socket.send(bytes: ByteArray) {
        getOutputStream().write(bytes)
        getOutputStream().flush()
    }

    private fun response(statusLine: String, body: Int): ByteArray =
        "$statusLine\r\nContent-Length: $body\r\n\r\n".toByteArray() + ByteArray(body)

    private companion object {
        const val HOST = "localhost"
        const val MAX_BYTES = 16_384
        const val TIMEOUT_MILLIS = 300
        const val DEADLINE_MILLIS = 5_000L
        val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()
    }
}
