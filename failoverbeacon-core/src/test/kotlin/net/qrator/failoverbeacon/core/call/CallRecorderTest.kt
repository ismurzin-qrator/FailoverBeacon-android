package net.qrator.failoverbeacon.core.call

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.call.mapper.TestFailureMapper
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.core.call.state.CallStopReason
import net.qrator.failoverbeacon.core.call.state.ConnectStopReason

class CallRecorderTest {
    private var time = 0L
    private val published = mutableListOf<Exchange>()
    private val recorder = CallRecorder({ time }, TestFailureMapper, published::add)

    @Test
    fun `a pooled connection to another server ends the exchange the app gave up`() {
        start(host = "primary.com")
        recorder.targetSelected("primary.com", 443)
        connect("1.1.1.1", failure = REFUSED)
        acquire("2.2.2.2", host = "backup.com")
        respond(200)
        recorder.callEnd()

        val (primary, backup) = published
        assertEquals("primary.com", primary.request.serverAddress)
        assertEquals("backup.com", backup.request.serverAddress)
        assertTrue(backup.reusedConnection)
    }

    @Test
    fun `a follow-up to an unnamed server keeps its failed connect when it takes a pooled one`() {
        start()
        acquire()
        respond(302)
        connect("2.2.2.2", failure = REFUSED)
        acquire("2.2.2.3", host = "b.com")
        respond(200)
        recorder.callEnd()

        assertEquals(2, published.size)
        assertEquals("b.com", published.last().request.serverAddress)
        assertEquals(listOf("2.2.2.2"), published.last().attempts.map { it.peerAddress })
    }

    @Test
    fun `the selected target names an open exchange that has none`() {
        start()
        acquire()
        respond(302)
        recorder.connectStart("2.2.2.2", 443, ProxyType.DIRECT)
        recorder.targetSelected("b.com", 443)
        recorder.callFailed(REFUSED, CallStopReason.FAILED)

        assertEquals("b.com", published.last().request.serverAddress)
    }

    @Test
    fun `the acquired connection names a follow-up, its method stays unknown`() {
        start()
        acquire()
        respond(307)
        acquire("2.2.2.2", port = 8443, host = "b.com")
        recorder.callEnd()

        val followUp = published.last().request
        assertEquals("b.com", followUp.serverAddress)
        assertEquals(8443, followUp.serverPort)
        assertNull(followUp.method)
    }

    @Test
    fun `a TCP failure goes to the earliest open connect, a handshake to the latest`() {
        start()
        at(0) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(10) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(20) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(30) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(40) { connectFailed("1.1.1.1", REFUSED) }
        at(50) { connectFailed("1.1.1.1", RESET, ExchangeStep.TLS) }
        at(60) { connectEnd("1.1.1.1", 443) }
        at(70) { connectFailed("1.1.1.1", CLOSED, stop = ConnectStopReason.CLIENT) }
        acquire()
        recorder.callEnd()

        val attempts = published.single().attempts
        assertEquals(
            listOf(
                ConnectResult.Failed(ExchangeStep.TCP, FailureType.REFUSED),
                ConnectResult.Superseded,
                ConnectResult.Connected,
                ConnectResult.Failed(ExchangeStep.TLS, FailureType.RESET)
            ),
            attempts.map { it.result }
        )
        assertEquals(listOf(40L, 60L, 40L, 20L), attempts.map { it.connectNanos })
    }

    @Test
    fun `a connect OkHttp dropped before the winner started is a failure`() {
        start()
        at(0) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(100) { connectFailed("1.1.1.1", CLOSED, stop = ConnectStopReason.CLIENT) }
        at(200) { connectStart("1.1.1.2", 443, ProxyType.DIRECT) }
        at(300) { connectEnd("1.1.1.2", 443) }
        acquire("1.1.1.2")
        recorder.callEnd()

        assertEquals(
            listOf(
                ConnectResult.Failed(ExchangeStep.TCP, FailureType.CLOSED),
                ConnectResult.Connected
            ),
            published.single().attempts.map { it.result }
        )
    }

    @Test
    fun `a connect OkHttp dropped during a failed handshake is superseded by it`() {
        start()
        at(0) { connectStart("1.1.1.3", 443, ProxyType.DIRECT) }
        at(20) { connectFailed("1.1.1.3", CLOSED, stop = ConnectStopReason.CLIENT) }
        at(30) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(35) { tlsStart() }
        at(50) { connectStart("1.1.1.2", 443, ProxyType.DIRECT) }
        at(100) { connectFailed("1.1.1.1", RESET, ExchangeStep.TLS) }
        at(120) { connectFailed("1.1.1.2", CLOSED, stop = ConnectStopReason.CLIENT) }
        recorder.callFailed(RESET, CallStopReason.FAILED)

        assertEquals(
            listOf(
                ConnectResult.Failed(ExchangeStep.TCP, FailureType.CLOSED),
                ConnectResult.Failed(ExchangeStep.TLS, FailureType.RESET),
                ConnectResult.Superseded
            ),
            published.single().attempts.map { it.result }
        )
    }

    @Test
    fun `the furthest failed connect decides the outcome`() {
        start()
        at(0) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(1) { tlsStart() }
        at(5) { connectFailed("1.1.1.1", SSLHandshakeException("untrusted"), ExchangeStep.TLS) }
        connect("1.1.1.2", start = 6, end = 7, failure = REFUSED)
        recorder.callFailed(REFUSED, CallStopReason.FAILED)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.TLS, FailureType.CERT_UNTRUSTED),
            published.single().outcome
        )
    }

    @Test
    fun `among connects failed at one step the last to end decides`() {
        start()
        at(0) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        connect("1.1.1.2", start = 6, end = 10, failure = REFUSED)
        at(600) { connectFailed("1.1.1.1", SocketTimeoutException("connect timed out")) }
        recorder.callFailed(REFUSED, CallStopReason.FAILED)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.TCP, FailureType.TIMED_OUT),
            published.single().outcome
        )
    }

    @Test
    fun `a cancel stops the open connects and is canceled at the step in progress`() {
        start()
        at(0) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(1) { tlsStart() }
        at(5) { connectFailed("1.1.1.1", RESET, ExchangeStep.TLS) }
        at(6) { connectStart("1.1.1.2", 443, ProxyType.DIRECT) }
        at(7) { connectStart("1.1.1.3", 443, ProxyType.DIRECT) }
        at(8) { tlsStart() }
        at(700) { connectFailed("1.1.1.2", CLOSED, stop = ConnectStopReason.CALL) }
        recorder.callFailed(IOException("Canceled"), CallStopReason.CANCELED)

        val exchange = published.single()
        assertEquals(
            listOf(
                ConnectResult.Failed(ExchangeStep.TLS, FailureType.RESET),
                ConnectResult.Canceled,
                ConnectResult.Canceled
            ),
            exchange.attempts.map { it.result }
        )
        assertEquals(ExchangeOutcome.Canceled(ExchangeStep.TLS), exchange.outcome)
    }

    @Test
    fun `a call timeout fails the connects it stopped and outranks earlier failures`() {
        start()
        at(0) { connectStart("1.1.1.1", 443, ProxyType.DIRECT) }
        at(1) { tlsStart() }
        at(5) { connectFailed("1.1.1.1", RESET, ExchangeStep.TLS) }
        at(6) { connectStart("1.1.1.2", 443, ProxyType.DIRECT) }
        at(700) { connectFailed("1.1.1.2", CLOSED, stop = ConnectStopReason.CALL) }
        recorder.callFailed(InterruptedIOException("timeout"), CallStopReason.TIMED_OUT)

        val exchange = published.single()
        assertEquals(
            listOf(
                ConnectResult.Failed(ExchangeStep.TLS, FailureType.RESET),
                ConnectResult.Failed(ExchangeStep.TCP, FailureType.TIMED_OUT)
            ),
            exchange.attempts.map { it.result }
        )
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.TCP, FailureType.TIMED_OUT),
            exchange.outcome
        )
    }

    @Test
    fun `an upload failing in the app's body fails at REQUEST`() {
        start(method = "POST")
        acquire()
        recorder.requestHeadersStart()
        recorder.requestHeadersEnd("a.com", 443, "POST")
        recorder.callFailed(IOException("file is gone"), CallStopReason.FAILED)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.REQUEST, FailureType.OTHER),
            published.single().outcome
        )
    }

    @Test
    fun `an early response after a failed request body is a response`() {
        start(method = "POST")
        acquire()
        recorder.requestHeadersStart()
        recorder.requestFailed(SocketException("Broken pipe"))
        recorder.responseHeadersEnd(413, 0)
        recorder.callEnd()

        val exchange = published.single()
        assertEquals(413, exchange.response?.statusCode)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
    }

    @Test
    fun `a request body ending after the response leaves no wait time`() {
        start(method = "POST")
        acquire()
        at(0) { requestHeadersStart() }
        at(1) { requestHeadersEnd("a.com", 443, "POST") }
        at(5) { responseHeadersEnd(200, null) }
        at(9) { requestBodyEnd(10) }
        recorder.callEnd()

        assertNull(published.single().timings.waitNanos)
    }

    @Test
    fun `a body cut fails at RESPONSE, keeps the status and is received from the headers`() {
        val timeout = SocketTimeoutException("read")
        start()
        acquire()
        at(0) { requestHeadersStart() }
        at(1) { requestHeadersEnd("a.com", 443, "GET") }
        at(51) { responseHeadersEnd(200, 200_000) }
        at(451) { responseFailed(timeout) }
        recorder.callFailed(timeout, CallStopReason.FAILED)

        val exchange = published.single()
        assertEquals(200_000L, exchange.response?.contentLength)
        assertEquals(50L, exchange.timings.waitNanos)
        assertEquals(400L, exchange.timings.receiveNanos)
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.RESPONSE, FailureType.TIMED_OUT),
            exchange.outcome
        )
    }

    @Test
    fun `a followed-up response ends at its headers`() {
        start()
        at(0) { acquire() }
        at(1) { requestHeadersStart() }
        at(10) { responseHeadersEnd(401, 6) }
        at(300) { responseBodyEnd(0) }
        at(310) { requestHeadersStart() }
        recorder.responseHeadersEnd(200, null)
        recorder.callEnd()

        val challenge = published.first()
        assertEquals(10L, challenge.durationNanos)
        assertNull(challenge.timings.receiveNanos)
    }

    @Test
    fun `a call failure after a complete response leaves it Ok, ending at its headers`() {
        for (reason in listOf(
            CallStopReason.FAILED,
            CallStopReason.CANCELED,
            CallStopReason.TIMED_OUT
        )) {
            val published = mutableListOf<Exchange>()
            val recorder = CallRecorder({ time }, TestFailureMapper, published::add)
            time = 0
            recorder.callStart("a.com", 443, "GET")
            recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2", ProxyType.DIRECT, TLS)
            time = 1
            recorder.requestHeadersStart()
            time = 10
            recorder.responseHeadersEnd(401, 6)
            time = 600
            recorder.responseBodyEnd(0)
            recorder.callFailed(IOException("stopped"), reason)

            val exchange = published.single()
            assertEquals(ExchangeOutcome.Ok, exchange.outcome, "$reason")
            assertEquals(10L, exchange.durationNanos, "$reason")
            assertNull(exchange.timings.receiveNanos, "$reason")
        }
    }

    @Test
    fun `a call that ended without a response or a failure failed without a cause`() {
        start()
        acquire()
        recorder.callEnd()

        assertEquals(ExchangeOutcome.Failed(null, type = null), published.single().outcome)
    }

    @Test
    fun `a failed DNS lookup ends when the failure is known`() {
        start()
        at(1) { dnsStart() }
        at(3_000) { callFailed(UnknownHostException("a.com"), CallStopReason.FAILED) }

        assertEquals(2_999L, published.single().durationNanos)
    }

    @Test
    fun `a connect to another port of the same IP did not make the connection`() {
        start()
        connect("1.1.1.1", port = 443)
        acquire("1.1.1.1", port = 8443)
        recorder.callEnd()

        assertTrue(published.single().reusedConnection)
        assertNull(published.single().timings.connectNanos)
    }

    @Test
    fun `an exchange without a connection reuses none`() {
        start()
        connect("1.1.1.1", failure = REFUSED)
        recorder.callFailed(REFUSED, CallStopReason.FAILED)

        assertFalse(published.single().reusedConnection)
    }

    @Test
    fun `a call publishes its first 20 exchanges and its last one`() {
        start()
        acquire()
        repeat(30) { respond(302) }
        recorder.callEnd()

        assertEquals((0..19) + 29, published.map { it.resendCount })
    }

    private fun start(method: String = "GET", host: String = "a.com") =
        recorder.callStart(host, 443, method)

    private fun acquire(ip: String = "1.1.1.1", port: Int = 443, host: String = "a.com") =
        recorder.connectionAcquired(ip, port, host, port, "h2", ProxyType.DIRECT, TLS)

    private fun respond(statusCode: Int) {
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(statusCode, null)
    }

    private fun connect(
        ip: String,
        port: Int = 443,
        start: Long = time,
        end: Long = time,
        failure: Throwable? = null
    ) {
        at(start) { connectStart(ip, port, ProxyType.DIRECT) }
        at(end) {
            if (failure == null) connectEnd(ip, port) else connectFailed(ip, failure, port = port)
        }
    }

    private fun CallRecorder.connectFailed(
        ip: String,
        failure: Throwable,
        step: ExchangeStep = ExchangeStep.TCP,
        stop: ConnectStopReason = ConnectStopReason.NETWORK,
        port: Int = 443
    ) = connectFailed(ip, port, failure, step, stop)

    private fun at(nanos: Long, step: CallRecorder.() -> Unit) {
        time = nanos
        recorder.step()
    }

    private companion object {
        val TLS = ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA")
        val REFUSED = ConnectException("refused")
        val RESET = SocketException("Connection reset")
        val CLOSED = SocketException("Socket closed")
    }
}
