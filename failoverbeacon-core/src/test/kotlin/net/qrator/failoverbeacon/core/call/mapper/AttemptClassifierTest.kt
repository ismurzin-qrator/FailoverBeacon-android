package net.qrator.failoverbeacon.core.call.mapper

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.state.AttemptState
import net.qrator.failoverbeacon.core.call.state.ConnectStopReason

/** Connect races of OkHttp 5 fast fallback, where timing decides the result. */
class AttemptClassifierTest {
    private val attempts = ArrayList<AttemptState>()

    @Test
    fun `a loser the client dropped before the winner connected is superseded`() {
        attempt(
            "1.1.1.1",
            start = 0,
            end = 270,
            failure = closed(),
            stop = ConnectStopReason.CLIENT
        )
        attempt("1.1.1.2", start = 250, end = 330)

        assertEquals(listOf(ConnectResult.Superseded, ConnectResult.Connected), results())
    }

    @Test
    fun `a loser still in flight is superseded`() {
        attempt("1.1.1.1", start = 0)
        attempt("1.1.1.2", start = 250, end = 330)

        assertEquals(ConnectResult.Superseded, results().first())
    }

    @Test
    fun `a connect the client dropped after the winner connected is superseded`() {
        attempt("1.1.1.1", start = 0, end = 10)
        attempt(
            "1.1.1.2",
            start = 15,
            end = 16,
            failure = IOException("canceled"),
            stop = ConnectStopReason.CLIENT
        )

        assertEquals(ConnectResult.Superseded, results().last())
    }

    @Test
    fun `a loser reporting late after the winner's handshake failed is still superseded`() {
        val reset = SocketException("Connection reset")
        attempt("1.1.1.1", start = 0, end = 300, failure = reset, tls = true)
        attempt(
            "1.1.1.2",
            start = 250,
            end = 380,
            failure = closed(),
            stop = ConnectStopReason.CLIENT
        )

        assertEquals(
            listOf(ConnectResult.Failed(ExchangeStep.TLS, reset), ConnectResult.Superseded),
            results()
        )
    }

    @Test
    fun `a racing connect the network failed is a failure`() {
        val timeout = SocketTimeoutException("connect timed out")
        val refused = ConnectException("refused")
        attempt(
            "1.1.1.1",
            start = 0,
            end = 600,
            failure = timeout,
            stop = ConnectStopReason.NETWORK
        )
        attempt(
            "1.1.1.2",
            start = 250,
            end = 305,
            failure = refused,
            stop = ConnectStopReason.NETWORK
        )
        attempt("1.1.1.3", start = 300, end = 450)

        assertEquals(
            listOf(
                ConnectResult.Failed(ExchangeStep.TCP, timeout),
                ConnectResult.Failed(ExchangeStep.TCP, refused),
                ConnectResult.Connected
            ),
            results()
        )
    }

    @Test
    fun `a connect dropped before the next one started is a failure`() {
        attempt("1.1.1.1", start = 0, end = 20, failure = closed(), stop = ConnectStopReason.CLIENT)
        attempt("1.1.1.2", start = 20, end = 40)

        assertEquals(
            ConnectResult.Failed(ExchangeStep.TCP, attempts[0].failure),
            results()[0]
        )
    }

    private fun results() =
        AttemptClassifier.classify(attempts, callStopReason = null, callFailure = null)

    private fun closed() = SocketException("Socket closed")

    private fun attempt(
        ip: String,
        start: Long,
        end: Long? = null,
        failure: Throwable? = null,
        stop: ConnectStopReason? = null,
        tls: Boolean = false
    ) {
        attempts += AttemptState(ip, 443, start).also {
            it.connectInterval.endNanos = end
            it.failure = failure
            it.failedStep = when {
                failure == null -> null
                tls -> ExchangeStep.TLS
                else -> ExchangeStep.TCP
            }
            it.stopReason = stop
        }
    }
}
