package net.qrator.failoverbeacon.core.call.state

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeStep

class ExchangeStateTest {
    private val exchange = ExchangeState("a.com", 443, "GET", resendCount = 0, startNanos = 0)

    @Test
    fun `has ended once it got a response or a failure`() {
        assertFalse(exchange.ended)

        exchange.statusCode = 200

        assertTrue(exchange.responded)
        assertTrue(exchange.ended)
        assertTrue(ExchangeState(null, null, null, 1, 0).apply { failure = IOException() }.ended)
    }

    @Test
    fun `finds the earliest connecting attempt to an address`() {
        val first = attempt("1.1.1.1", start = 0)
        val retry = attempt("1.1.1.1", start = 10)
        attempt("1.1.1.1", start = 20, end = 30)
        attempt("1.1.1.1", port = 8443, start = 40)

        assertSame(first, exchange.getConnectingAttempt("1.1.1.1", 443))
        first.connectInterval.endNanos = 50
        assertSame(retry, exchange.getConnectingAttempt("1.1.1.1", 443))
        assertNull(exchange.getConnectingAttempt("2.2.2.2", 443))
    }

    @Test
    fun `the connected attempt is the one to the server IP`() {
        attempt("1.1.1.1", start = 0, end = 10)
        val carrier = attempt("1.1.1.2", start = 20, end = 30)
        exchange.serverIp = "1.1.1.2"

        assertSame(carrier, exchange.connectedAttempt)
    }

    @Test
    fun `classifies a connected attempt`() {
        val attempt = attempt("1.1.1.1", start = 0, end = 10)

        assertEquals(ConnectResult.Connected, exchange.classifyAttempt(attempt))
    }

    @Test
    fun `a refused attempt is a TCP failure`() {
        val refused = ConnectException("refused")
        val attempt = attempt("1.1.1.1", start = 0, end = 10, failure = refused)

        assertEquals(
            ConnectResult.Failed(ExchangeStep.TCP, refused),
            exchange.classifyAttempt(attempt)
        )
    }

    @Test
    fun `an attempt failed with an SSLException is a TLS failure`() {
        val untrusted = SSLHandshakeException("untrusted")
        val attempt = attempt("1.1.1.1", start = 0, end = 10, failure = untrusted)

        assertEquals(
            ConnectResult.Failed(ExchangeStep.TLS, untrusted),
            exchange.classifyAttempt(attempt)
        )
    }

    @Test
    fun `a handshake cut by RST belongs to the only attempt in flight`() {
        val reset = SocketException("Connection reset")
        val attempt = attempt("1.1.1.1", start = 0, end = 20, failure = reset)
        exchange.tlsStartTimes += 10

        assertEquals(
            ConnectResult.Failed(ExchangeStep.TLS, reset),
            exchange.classifyAttempt(attempt)
        )
    }

    @Test
    fun `a racing loser canceled before the winner connected is superseded`() {
        val loser =
            attempt("1.1.1.1", start = 0, end = 270, failure = SocketException("Socket closed"))
        val winner = attempt("1.1.1.2", start = 250, end = 330)
        exchange.tlsStartTimes += 280

        assertEquals(ConnectResult.Superseded, exchange.classifyAttempt(loser))
        assertEquals(ConnectResult.Connected, exchange.classifyAttempt(winner))
    }

    @Test
    fun `a racing loser still in flight is superseded`() {
        val loser = attempt("1.1.1.1", start = 0)
        attempt("1.1.1.2", start = 250, end = 330)

        assertEquals(ConnectResult.Superseded, exchange.classifyAttempt(loser))
    }

    @Test
    fun `a racing attempt that timed out is a failure`() {
        val timeout = SocketTimeoutException("connect timed out")
        val slow = attempt("1.1.1.1", start = 0, end = 600, failure = timeout)
        attempt("1.1.1.2", start = 250, end = 450)

        assertEquals(
            ConnectResult.Failed(ExchangeStep.TCP, timeout),
            exchange.classifyAttempt(slow)
        )
    }

    @Test
    fun `a racing attempt refused while the winner connects is a failure`() {
        val refused = ConnectException("refused")
        val attempt = attempt("1.1.1.1", start = 0, end = 305, failure = refused)
        attempt("1.1.1.2", start = 250, end = 660)

        assertEquals(
            ConnectResult.Failed(ExchangeStep.TCP, refused),
            exchange.classifyAttempt(attempt)
        )
    }

    @Test
    fun `an attempt that ended before the next started is not superseded`() {
        val closed = SocketException("Socket closed")
        val first = attempt("1.1.1.1", start = 0, end = 20, failure = closed)
        attempt("1.1.1.2", start = 20, end = 40)

        assertEquals(
            ConnectResult.Failed(ExchangeStep.TCP, closed),
            exchange.classifyAttempt(first)
        )
    }

    @Test
    fun `in a race the handshake belongs to the attempt that ended last`() {
        val loser =
            attempt("1.1.1.1", start = 0, end = 280, failure = SocketException("Socket closed"))
        val reset = SocketException("Connection reset")
        val winner = attempt("1.1.1.2", start = 250, end = 300, failure = reset)
        exchange.tlsStartTimes += 271

        assertEquals(setOf(winner), exchange.tlsOwners)
        assertEquals(ConnectResult.Superseded, exchange.classifyAttempt(loser))
        assertEquals(
            ConnectResult.Failed(ExchangeStep.TLS, reset),
            exchange.classifyAttempt(winner)
        )
    }

    @Test
    fun `a race handshake has an owner even if a later retry connected`() {
        val loser =
            attempt("1.1.1.1", start = 0, end = 280, failure = SocketException("Socket closed"))
        val reset = SocketException("Connection reset")
        val cut = attempt("1.1.1.2", start = 250, end = 300, failure = reset)
        val retry = attempt("1.1.1.1", start = 310, end = 400)
        exchange.tlsStartTimes += listOf(271L, 320L)

        assertEquals(ConnectResult.Superseded, exchange.classifyAttempt(loser))
        assertEquals(ConnectResult.Failed(ExchangeStep.TLS, reset), exchange.classifyAttempt(cut))
        assertEquals(ConnectResult.Connected, exchange.classifyAttempt(retry))
    }

    @Test
    fun `a handshake has no owner when an attempt in flight connected`() {
        attempt("1.1.1.1", start = 0, end = 280, failure = SocketException("Socket closed"))
        attempt("1.1.1.2", start = 250, end = 300)
        exchange.tlsStartTimes += 271

        assertTrue(exchange.tlsOwners.isEmpty())
    }

    private fun attempt(
        ip: String,
        port: Int = 443,
        start: Long,
        end: Long? = null,
        failure: Throwable? = null
    ): AttemptState = AttemptState(ip, port, start).also {
        it.connectInterval.endNanos = end
        it.failure = failure
        exchange.attempts += it
    }
}
