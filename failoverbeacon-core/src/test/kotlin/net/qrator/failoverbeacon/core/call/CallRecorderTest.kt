package net.qrator.failoverbeacon.core.call

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeStep

class CallRecorderTest {
    private var time = 0L
    private val published = mutableListOf<Exchange>()
    private val recorder = CallRecorder({ time }, published::add)

    @Test
    fun `records a call as one exchange`() {
        recorder.callStart("a.com", 443, "GET")
        at(1) { dnsStart() }
        at(3) { dnsEnd() }
        connect("1.1.1.1", start = 4, end = 10)
        at(10) { connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2") }
        at(11) { requestHeadersStart() }
        at(12) { requestHeadersEnd("a.com", 443, "GET") }
        at(20) { responseHeadersEnd(200, "h2", 5) }
        at(21) { responseBodyStart() }
        at(23) { responseBodyEnd(5) }
        at(30) { callEnd() }

        val exchange = published.single()
        assertEquals(ExchangeRequest("a.com", 443, "GET", bodyBytes = null), exchange.request)
        assertEquals(200, exchange.response?.statusCode)
        assertEquals(5L, exchange.response?.bodyBytes)
        assertEquals("1.1.1.1", exchange.serverIp)
        assertEquals("h2", exchange.protocol)
        assertFalse(exchange.reusedConnection)
        assertEquals(ConnectResult.Connected, exchange.attempts.single().result)
        assertEquals(2L, exchange.timings.dnsNanos)
        assertEquals(6L, exchange.timings.connectNanos)
        assertEquals(8L, exchange.timings.waitNanos)
        assertEquals(2L, exchange.timings.receiveNanos)
        assertEquals(22L, exchange.durationNanos)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
    }

    @Test
    fun `publishes an exchange only once it ends`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)

        assertTrue(published.isEmpty())

        recorder.callEnd()
        recorder.callEnd()

        assertEquals(1, published.size)
    }

    @Test
    fun `a follow-up is a new exchange and publishes the previous one`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(302, "h2", 0)
        recorder.dnsStart()

        assertEquals(302, published.single().response?.statusCode)

        connect("2.2.2.2")
        recorder.connectionAcquired("2.2.2.2", 443, "b.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.requestHeadersEnd("b.com", 443, "GET")
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        val (redirect, followUp) = published
        assertEquals("a.com", redirect.request.serverAddress)
        assertEquals(0, redirect.resendCount)
        assertEquals("b.com", followUp.request.serverAddress)
        assertEquals("2.2.2.2", followUp.serverIp)
        assertEquals(1, followUp.resendCount)
    }

    @Test
    fun `a follow-up on the connection the call holds reuses it`() {
        recorder.callStart("a.com", 443, "GET")
        connect("1.1.1.1")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(401, "h2", 0)
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        val (challenge, retry) = published
        assertFalse(challenge.reusedConnection)
        assertEquals("1.1.1.1", retry.serverIp)
        assertEquals("h2", retry.protocol)
        assertTrue(retry.reusedConnection)
    }

    @Test
    fun `a follow-up target is unknown until OkHttp names it`() {
        recorder.callStart("a.com", 443, "POST")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(303, "h2", 0)
        connect("1.1.1.1", port = 8443, failure = ConnectException("refused"))
        recorder.callFailed(ConnectException("refused"), canceled = false, timedOut = false)

        val (redirect, followUp) = published
        assertEquals("POST", redirect.request.method)
        assertEquals(ExchangeRequest(null, null, null, bodyBytes = null), followUp.request)
    }

    @Test
    fun `the acquired connection names the follow-up target`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(307, "h2", 0)
        recorder.connectionAcquired("2.2.2.2", 8443, "b.com", 8443, "h2")
        recorder.callEnd()

        val followUp = published.last().request
        assertEquals("b.com", followUp.serverAddress)
        assertEquals(8443, followUp.serverPort)
        assertNull(followUp.method)
    }

    @Test
    fun `the DNS lookup does not rename the target`() {
        recorder.callStart("target.com", 443, "GET")
        recorder.dnsStart()
        connect("10.0.0.5", port = 3128, failure = ConnectException("refused"))
        recorder.callFailed(ConnectException("refused"), canceled = false, timedOut = false)

        assertEquals("target.com", published.single().request.serverAddress)
    }

    @Test
    fun `a retry after a failed request is a new exchange`() {
        recorder.callStart("a.com", 443, "POST")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.requestFailed(SocketException("reset"))
        connect("1.1.1.1")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        val (stale, retry) = published
        assertTrue(stale.reusedConnection)
        assertTrue(stale.attempts.isEmpty())
        assertEquals(ExchangeStep.REQUEST, (stale.outcome as ExchangeOutcome.Failed).step)
        assertFalse(retry.reusedConnection)
        assertEquals(ExchangeOutcome.Ok, retry.outcome)
    }

    @Test
    fun `connects that sent nothing stay in one exchange`() {
        recorder.callStart("a.com", 443, "GET")
        connect("1.1.1.1", failure = SocketTimeoutException("connect timed out"))
        connect("1.1.1.2")
        recorder.connectionAcquired("1.1.1.2", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        val exchange = published.single()
        assertEquals(listOf("1.1.1.1", "1.1.1.2"), exchange.attempts.map { it.peerAddress })
        assertEquals("1.1.1.2", exchange.serverIp)
    }

    @Test
    fun `a connect failure goes to the earliest attempt to that address`() {
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectStart("1.1.1.1", 443) }
        at(10) { connectStart("1.1.1.1", 443) }
        at(20) { connectFailed("1.1.1.1", 443, SocketException("Socket closed")) }
        at(30) { connectEnd("1.1.1.1", 443) }
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.callEnd()

        val (canceled, retry) = published.single().attempts
        assertEquals(20L, canceled.connectNanos)
        assertEquals(ConnectResult.Connected, retry.result)
        assertEquals(20L, retry.connectNanos)
    }

    @Test
    fun `a handshake cut in a race belongs to the attempt that ended last`() {
        val reset = SocketException("Connection reset")
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectStart("1.1.1.1", 443) }
        at(250) { connectStart("1.1.1.2", 443) }
        at(271) { tlsStart() }
        at(280) { connectFailed("1.1.1.1", 443, SocketException("Socket closed")) }
        at(300) { connectFailed("1.1.1.2", 443, reset) }
        at(310) { connectStart("1.1.1.1", 443) }
        at(320) { tlsStart() }
        at(330) { connectEnd("1.1.1.1", 443) }
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.callEnd()

        assertEquals(
            listOf(
                ConnectResult.Superseded,
                ConnectResult.Failed(ExchangeStep.TLS, reset),
                ConnectResult.Connected
            ),
            published.single().attempts.map { it.result }
        )
    }

    @Test
    fun `an early response after a failed request body is a response`() {
        recorder.callStart("a.com", 443, "POST")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.requestFailed(SocketException("Broken pipe"))
        recorder.responseHeadersEnd(413, "h2", 0)
        recorder.callEnd()

        val exchange = published.single()
        assertEquals(413, exchange.response?.statusCode)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
    }

    @Test
    fun `a body cut fails at RESPONSE and keeps the status`() {
        val timeout = SocketTimeoutException("read")
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", 200_000)
        recorder.responseBodyStart()
        recorder.responseFailed(timeout)
        recorder.callFailed(timeout, canceled = false, timedOut = false)

        val exchange = published.single()
        assertEquals(200, exchange.response?.statusCode)
        assertEquals(200_000L, exchange.response?.contentLength)
        assertNull(exchange.response?.bodyBytes)
        assertEquals(ExchangeOutcome.Failed(ExchangeStep.RESPONSE, timeout), exchange.outcome)
    }

    @Test
    fun `a failure while waiting for the headers ends the wait`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        at(0) { requestHeadersStart() }
        at(1) { requestHeadersEnd("a.com", 443, "GET") }
        at(10_001) { responseFailed(SocketTimeoutException("read")) }
        recorder.callFailed(SocketTimeoutException("read"), canceled = false, timedOut = false)

        val exchange = published.single()
        assertEquals(10_000L, exchange.timings.waitNanos)
        assertNull(exchange.timings.receiveNanos)
    }

    @Test
    fun `a request body ending after the response leaves no wait time`() {
        recorder.callStart("a.com", 443, "POST")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        at(0) { requestHeadersStart() }
        at(1) { requestHeadersEnd("a.com", 443, "POST") }
        at(5) { responseHeadersEnd(200, "h2", null) }
        at(9) { requestBodyEnd(10) }
        recorder.callEnd()

        assertNull(published.single().timings.waitNanos)
    }

    @Test
    fun `the last exchange lasts until its body ends`() {
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2") }
        at(10) { requestHeadersStart() }
        at(20) { responseHeadersEnd(200, "h2", 5) }
        at(30) { responseBodyStart() }
        at(40) { responseBodyEnd(5) }
        at(1_000) { callEnd() }

        assertEquals(40L, published.single().durationNanos)
    }

    @Test
    fun `a redirect loop fails the last exchange`() {
        val loop = ProtocolException("Too many follow-up requests: 21")
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(302, "h2", 0)
        recorder.callFailed(loop, canceled = false, timedOut = false)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.RESPONSE, loop),
            published.single().outcome
        )
    }

    @Test
    fun `a failure before the follow-up reaches the network leaves the response alone`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(302, "h2", 0)
        recorder.callFailed(
            UnknownServiceException("CLEARTEXT communication not permitted"),
            canceled = false,
            timedOut = false
        )

        assertEquals(ExchangeOutcome.Ok, published.single().outcome)
    }

    @Test
    fun `a call timeout wins over the read failure it caused`() {
        val timeout = InterruptedIOException("timeout")
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.responseBodyStart()
        recorder.responseFailed(SocketException("Socket closed"))
        recorder.callFailed(timeout, canceled = false, timedOut = true)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.RESPONSE, timeout),
            published.single().outcome
        )
    }

    @Test
    fun `a canceled call publishes a canceled exchange`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.dnsStart()
        recorder.callFailed(IOException("Canceled"), canceled = true, timedOut = false)

        assertEquals(ExchangeOutcome.Canceled, published.single().outcome)
    }

    @Test
    fun `a failed DNS lookup fails at DNS`() {
        val unresolved = UnknownHostException("a.com")
        recorder.callStart("a.com", 443, "GET")
        recorder.dnsStart()
        recorder.callFailed(unresolved, canceled = false, timedOut = false)

        val exchange = published.single()
        assertEquals("a.com", exchange.request.serverAddress)
        assertEquals(ExchangeOutcome.Failed(ExchangeStep.DNS, unresolved), exchange.outcome)
    }

    @Test
    fun `a call that never reached the network publishes nothing`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.callEnd()

        assertTrue(published.isEmpty())
    }

    @Test
    fun `a call that ended without a response or a failure failed without a cause`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.callEnd()

        assertEquals(ExchangeOutcome.Failed(null, cause = null), published.single().outcome)
    }

    private fun at(nanos: Long, step: CallRecorder.() -> Unit) {
        time = nanos
        recorder.step()
    }

    private fun connect(
        ip: String,
        port: Int = 443,
        start: Long = time,
        end: Long = time,
        failure: Throwable? = null
    ) {
        at(start) { connectStart(ip, port) }
        at(end) {
            if (failure == null) connectEnd(ip, port) else connectFailed(ip, port, failure)
        }
    }
}
