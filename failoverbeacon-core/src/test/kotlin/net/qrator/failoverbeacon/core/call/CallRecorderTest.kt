package net.qrator.failoverbeacon.core.call

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
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
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.core.call.state.CallStopReason
import net.qrator.failoverbeacon.core.call.state.ConnectStopReason

class CallRecorderTest {
    private var time = 0L
    private val published = mutableListOf<Exchange>()
    private val recorder = CallRecorder({ time }, TestFailureMapper, published::add)

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
        assertEquals(3L, exchange.timings.receiveNanos)
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
    fun `ignores steps that come before the call reaches the network`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.dnsEnd()
        recorder.tlsStart()
        recorder.tlsEnd()
        recorder.connectEnd("1.1.1.1", 443)
        recorder.connectFailed(
            "1.1.1.1",
            443,
            IOException(),
            ExchangeStep.TCP,
            ConnectStopReason.NETWORK
        )
        recorder.requestHeadersEnd("a.com", 443, "GET")
        recorder.requestBodyEnd(1)
        recorder.requestFailed(IOException())
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.responseBodyEnd(1)
        recorder.responseFailed(IOException())
        recorder.callEnd()

        assertTrue(published.isEmpty())
    }

    @Test
    fun `ignores steps reported after the call ended`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        recorder.connectStart("1.1.1.2", 443)
        recorder.dnsStart()
        recorder.connectionAcquired("1.1.1.2", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()

        assertEquals(1, published.size)
    }

    @Test
    fun `a call that ended without a response or a failure failed without a cause`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.callEnd()

        assertEquals(ExchangeOutcome.Failed(null, type = null), published.single().outcome)
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
    fun `every exchange of a call carries its call ID`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(302, "h2", 0)
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        assertEquals(2, published.size)
        assertEquals(1, published.map { it.callId }.distinct().size)
    }

    @Test
    fun `a followed-up response ends at its headers`() {
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2") }
        at(1) { requestHeadersStart() }
        at(10) { responseHeadersEnd(401, "h2", 6) }
        at(300) { responseBodyEnd(0) }
        at(310) { requestHeadersStart() }
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        val challenge = published.first()
        assertEquals(10L, challenge.durationNanos)
        assertNull(challenge.timings.receiveNanos)
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
    fun `a connect to another port of the same IP is not this connection`() {
        recorder.callStart("a.com", 443, "GET")
        connect("1.1.1.1", port = 443)
        recorder.connectionAcquired("1.1.1.1", 8443, "a.com", 8443, "h2")
        recorder.callEnd()

        assertTrue(published.single().reusedConnection)
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
    fun `a follow-up target is unknown until OkHttp names it`() {
        recorder.callStart("a.com", 443, "POST")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(303, "h2", 0)
        connect("1.1.1.1", port = 8443, failure = ConnectException("refused"))
        recorder.callFailed(ConnectException("refused"), CallStopReason.FAILED)

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
    fun `a resend keeps the target and method of the failed request`() {
        recorder.callStart("a.com", 443, "POST")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.requestHeadersEnd("a.com", 443, "POST")
        recorder.responseFailed(SocketException("Connection reset"))
        connect("1.1.1.1", failure = ConnectException("refused"))
        recorder.callFailed(ConnectException("refused"), CallStopReason.FAILED)

        assertEquals(
            ExchangeRequest("a.com", 443, "POST", bodyBytes = null),
            published.last().request
        )
    }

    @Test
    fun `the selected target replaces the one the app asked for`() {
        recorder.callStart("primary.com", 443, "GET")
        recorder.targetSelected("backup.com", 8443)
        recorder.dnsStart()
        recorder.callFailed(UnknownHostException("backup.com"), CallStopReason.FAILED)

        assertEquals(
            ExchangeRequest("backup.com", 8443, "GET", bodyBytes = null),
            published.single().request
        )
    }

    @Test
    fun `the selected target names a follow-up before it is sent`() {
        val unresolved = UnknownHostException("b.com")
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(302, "h2", 0)
        recorder.targetSelected("b.com", 8443)
        recorder.dnsStart()
        recorder.callFailed(unresolved, CallStopReason.FAILED)

        val followUp = published.last()
        assertEquals("b.com", followUp.request.serverAddress)
        assertEquals(8443, followUp.request.serverPort)
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.DNS, FailureType.NAME_NOT_RESOLVED),
            followUp.outcome
        )
    }

    @Test
    fun `the selected target names an open exchange that has none`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(302, "h2", 0)
        recorder.connectStart("2.2.2.2", 443)
        recorder.targetSelected("b.com", 443)
        recorder.callFailed(ConnectException("refused"), CallStopReason.FAILED)

        assertEquals("b.com", published.last().request.serverAddress)
    }

    @Test
    fun `an exchange given up for another server ends at its last network event`() {
        val refused = ConnectException("refused")
        recorder.callStart("primary.com", 443, "GET")
        at(0) { targetSelected("primary.com", 443) }
        at(1) { dnsStart() }
        at(2) { dnsEnd() }
        connect("1.1.1.1", start = 3, end = 5, failure = refused)
        at(505) { targetSelected("backup.com", 443) }
        recorder.dnsStart()
        connect("2.2.2.2")
        recorder.connectionAcquired("2.2.2.2", 443, "backup.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        val (primary, backup) = published
        assertEquals("primary.com", primary.request.serverAddress)
        assertEquals(ExchangeOutcome.Failed(ExchangeStep.TCP, FailureType.REFUSED), primary.outcome)
        assertEquals(4L, primary.durationNanos)
        assertEquals("backup.com", backup.request.serverAddress)
        assertEquals(1, backup.resendCount)
        assertEquals(ExchangeOutcome.Ok, backup.outcome)
    }

    @Test
    fun `a pooled connection to another server ends the exchange given up`() {
        recorder.callStart("primary.com", 443, "GET")
        recorder.targetSelected("primary.com", 443)
        recorder.dnsStart()
        connect("1.1.1.1", failure = ConnectException("refused"))
        recorder.connectionAcquired("2.2.2.2", 443, "backup.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        val (primary, backup) = published
        assertEquals("primary.com", primary.request.serverAddress)
        assertTrue(backup.reusedConnection)
        assertTrue(backup.attempts.isEmpty())
    }

    @Test
    fun `the same server selected again keeps the exchange`() {
        recorder.callStart("a.com", 443, "GET")
        recorder.targetSelected("a.com", 443)
        recorder.dnsStart()
        connect("1.1.1.1", failure = SocketTimeoutException("connect timed out"))
        recorder.targetSelected("a.com", 443)
        connect("1.1.1.2")
        recorder.connectionAcquired("1.1.1.2", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.callEnd()

        assertEquals(2, published.single().attempts.size)
    }

    @Test
    fun `a failed DNS lookup fails at DNS and ends when the failure is known`() {
        val unresolved = UnknownHostException("a.com")
        recorder.callStart("a.com", 443, "GET")
        at(1) { dnsStart() }
        at(3_000) { callFailed(unresolved, CallStopReason.FAILED) }

        val exchange = published.single()
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.DNS, FailureType.NAME_NOT_RESOLVED),
            exchange.outcome
        )
        assertEquals(2_999L, exchange.durationNanos)
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
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
    }

    @Test
    fun `a connect finishing its handshake is the latest to its address`() {
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectStart("1.1.1.1", 443) }
        at(300) { connectStart("1.1.1.1", 443) }
        at(320) { connectEnd("1.1.1.1", 443) }
        at(380) {
            connectFailed(
                "1.1.1.1",
                443,
                SocketException("Socket closed"),
                ExchangeStep.TCP,
                ConnectStopReason.CLIENT
            )
        }
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.callEnd()

        val (late, retry) = published.single().attempts
        assertEquals(ConnectResult.Superseded, late.result)
        assertEquals(20L, retry.connectNanos)
    }

    @Test
    fun `a handshake failure in a race belongs to the connect that reported it`() {
        val reset = SocketException("Connection reset")
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectStart("1.1.1.1", 443) }
        at(250) { connectStart("1.1.1.2", 443) }
        at(271) { tlsStart() }
        at(280) {
            connectFailed(
                "1.1.1.1",
                443,
                SocketException("Socket closed"),
                ExchangeStep.TCP,
                ConnectStopReason.CLIENT
            )
        }
        at(300) {
            connectFailed("1.1.1.2", 443, reset, ExchangeStep.TLS, ConnectStopReason.NETWORK)
        }
        at(310) { connectStart("1.1.1.1", 443) }
        at(320) { tlsStart() }
        at(330) { connectEnd("1.1.1.1", 443) }
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.callEnd()

        assertEquals(
            listOf(
                ConnectResult.Superseded,
                ConnectResult.Failed(ExchangeStep.TLS, FailureType.RESET),
                ConnectResult.Connected
            ),
            published.single().attempts.map { it.result }
        )
    }

    @Test
    fun `the furthest failed connect decides the outcome`() {
        val untrusted = SSLHandshakeException("untrusted")
        val refused = ConnectException("refused")
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectStart("1.1.1.1", 443) }
        at(1) { tlsStart() }
        at(5) {
            connectFailed("1.1.1.1", 443, untrusted, ExchangeStep.TLS, ConnectStopReason.NETWORK)
        }
        at(6) { connectStart("1.1.1.2", 443) }
        at(7) {
            connectFailed("1.1.1.2", 443, refused, ExchangeStep.TCP, ConnectStopReason.NETWORK)
        }
        recorder.callFailed(refused, CallStopReason.FAILED)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.TLS, FailureType.CERT_UNTRUSTED),
            published.single().outcome
        )
    }

    @Test
    fun `among connects failed at one step the last to end decides`() {
        val refused = ConnectException("refused")
        val timeout = SocketTimeoutException("connect timed out")
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectStart("1.1.1.1", 443) }
        at(5) {
            connectFailed("1.1.1.1", 443, refused, ExchangeStep.TCP, ConnectStopReason.NETWORK)
        }
        at(6) { connectStart("1.1.1.2", 443) }
        at(600) {
            connectFailed("1.1.1.2", 443, timeout, ExchangeStep.TCP, ConnectStopReason.NETWORK)
        }
        recorder.callFailed(refused, CallStopReason.FAILED)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.TCP, FailureType.TIMED_OUT),
            published.single().outcome
        )
    }

    @Test
    fun `a canceled call cancels the connects it stopped and keeps earlier failures`() {
        val reset = SocketException("Connection reset")
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectStart("1.1.1.1", 443) }
        at(1) { tlsStart() }
        at(5) { connectFailed("1.1.1.1", 443, reset, ExchangeStep.TLS, ConnectStopReason.NETWORK) }
        at(6) { connectStart("1.1.1.2", 443) }
        at(7) { connectStart("1.1.1.3", 443) }
        at(700) {
            connectFailed(
                "1.1.1.2",
                443,
                SocketException("Socket closed"),
                ExchangeStep.TCP,
                ConnectStopReason.CALL
            )
        }
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
        assertEquals(ExchangeOutcome.Canceled, exchange.outcome)
    }

    @Test
    fun `a call timeout fails the connects it stopped and outranks earlier failures`() {
        val reset = SocketException("Connection reset")
        val timeout = InterruptedIOException("timeout")
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectStart("1.1.1.1", 443) }
        at(1) { tlsStart() }
        at(5) { connectFailed("1.1.1.1", 443, reset, ExchangeStep.TLS, ConnectStopReason.NETWORK) }
        at(6) { connectStart("1.1.1.2", 443) }
        at(700) {
            connectFailed(
                "1.1.1.2",
                443,
                SocketException("Socket closed"),
                ExchangeStep.TCP,
                ConnectStopReason.CALL
            )
        }
        recorder.callFailed(timeout, CallStopReason.TIMED_OUT)

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
    fun `a failed send keeps its first failure, has no wait and ends at the failure`() {
        val broken = SocketException("Broken pipe")
        recorder.callStart("a.com", 443, "POST")
        at(0) { connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2") }
        at(1) { requestHeadersStart() }
        at(2) { requestHeadersEnd("a.com", 443, "POST") }
        at(50) { requestFailed(broken) }
        at(50) { responseFailed(SocketException("Socket closed")) }
        at(500) { connectStart("1.1.1.1", 443) }

        val exchange = published.single()
        assertEquals(49L, exchange.timings.sendNanos)
        assertNull(exchange.timings.waitNanos)
        assertEquals(50L, exchange.durationNanos)
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.REQUEST, FailureType.RESET),
            exchange.outcome
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
    fun `a failure while waiting for the headers ends the wait and the exchange`() {
        recorder.callStart("a.com", 443, "GET")
        at(0) { connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2") }
        at(1) { requestHeadersStart() }
        at(2) { requestHeadersEnd("a.com", 443, "GET") }
        at(302) { responseFailed(SocketTimeoutException("read")) }
        at(802) { callFailed(SocketTimeoutException("read"), CallStopReason.FAILED) }

        val exchange = published.single()
        assertEquals(300L, exchange.timings.waitNanos)
        assertNull(exchange.timings.receiveNanos)
        assertEquals(302L, exchange.durationNanos)
    }

    @Test
    fun `a body cut fails at RESPONSE, keeps the status and is received from the headers`() {
        val timeout = SocketTimeoutException("read")
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        at(0) { requestHeadersStart() }
        at(1) { requestHeadersEnd("a.com", 443, "GET") }
        at(51) { responseHeadersEnd(200, "h2", 200_000) }
        at(451) { responseFailed(timeout) }
        recorder.callFailed(timeout, CallStopReason.FAILED)

        val exchange = published.single()
        assertEquals(200, exchange.response?.statusCode)
        assertEquals(200_000L, exchange.response?.contentLength)
        assertNull(exchange.response?.bodyBytes)
        assertEquals(50L, exchange.timings.waitNanos)
        assertEquals(400L, exchange.timings.receiveNanos)
        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.RESPONSE, FailureType.TIMED_OUT),
            exchange.outcome
        )
    }

    @Test
    fun `a call timeout wins over the read failure it caused`() {
        val timeout = InterruptedIOException("timeout")
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(200, "h2", null)
        recorder.responseFailed(SocketException("Socket closed"))
        recorder.callFailed(timeout, CallStopReason.TIMED_OUT)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.RESPONSE, FailureType.TIMED_OUT),
            published.single().outcome
        )
    }

    @Test
    fun `a call failure after a complete response leaves it Ok, ending at its headers`() {
        val reasons =
            listOf(CallStopReason.FAILED, CallStopReason.CANCELED, CallStopReason.TIMED_OUT)
        for (reason in reasons) {
            val published = mutableListOf<Exchange>()
            val recorder = CallRecorder({ time }, TestFailureMapper, published::add)
            time = 0
            recorder.callStart("a.com", 443, "GET")
            recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
            time = 1
            recorder.requestHeadersStart()
            time = 10
            recorder.responseHeadersEnd(401, "h2", 6)
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
    fun `a redirect loop fails the last exchange`() {
        val loop = ProtocolException("Too many follow-up requests: 21")
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        recorder.requestHeadersStart()
        recorder.responseHeadersEnd(302, "h2", 0)
        recorder.callFailed(loop, CallStopReason.RESPONSE_REJECTED)

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.RESPONSE, FailureType.PROTOCOL_ERROR),
            published.single().outcome
        )
    }

    @Test
    fun `a call publishes its first 20 exchanges and its last one`() {
        exchanges(30)
        recorder.callEnd()

        assertEquals((0..19) + 29, published.map { it.resendCount })
    }

    @Test
    fun `a call of 21 exchanges publishes them all`() {
        exchanges(21)
        recorder.callEnd()

        assertEquals((0..20).toList(), published.map { it.resendCount })
    }

    @Test
    fun `a call that does not end publishes only its first 20 exchanges`() {
        exchanges(30)

        assertEquals((0..19).toList(), published.map { it.resendCount })
    }

    private fun exchanges(count: Int) {
        recorder.callStart("a.com", 443, "GET")
        recorder.connectionAcquired("1.1.1.1", 443, "a.com", 443, "h2")
        repeat(count) {
            recorder.requestHeadersStart()
            recorder.responseHeadersEnd(302, "h2", 0)
        }
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
            if (failure == null) {
                connectEnd(ip, port)
            } else {
                connectFailed(ip, port, failure, ExchangeStep.TCP, ConnectStopReason.NETWORK)
            }
        }
    }
}
