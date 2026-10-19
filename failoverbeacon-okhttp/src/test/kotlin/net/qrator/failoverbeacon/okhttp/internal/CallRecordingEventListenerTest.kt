package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.CallRecorder
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.okhttp.internal.mapper.OkHttpFailureMapper
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class CallRecordingEventListenerTest {
    private val logs = CopyOnWriteArrayList<Pair<String, Throwable?>>()
    private val published = CopyOnWriteArrayList<Exchange>()
    private val previousOutput = SdkRegistry.logger.output

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.output =
            LogOutput { _, message, throwable -> logs += message to throwable }
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = previousOutput
        CallRecordingEventListener.incompatible = false
        SdkRegistry.clear()
    }

    @Test
    fun `an SDK exception stays out of the call and is logged`() {
        val failure = IllegalStateException()
        val call = call(OkHttpClient())

        listener { throw failure }.dnsStart(call, "example.test")

        assertSame(failure, logs.single().second)
    }

    @Test
    fun `ignores the rest of the call after a failure`() {
        var clockReads = 0
        val call = call(OkHttpClient())
        val listener = listener {
            clockReads++
            throw IllegalStateException()
        }

        listener.dnsStart(call, "example.test")
        listener.dnsEnd(call, "example.test", emptyList())
        listener.callEnd(call)

        assertEquals(1, clockReads)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `logs only the first failure`() {
        val call = call(OkHttpClient())

        listener { throw IllegalStateException() }.dnsStart(call, "example.test")
        listener { throw IllegalStateException() }.dnsStart(call, "example.test")

        assertEquals(1, logs.size)
    }

    @Test
    fun `a linkage error marks the OkHttp version incompatible`() {
        listener { throw NoSuchMethodError() }.dnsStart(call(OkHttpClient()), "example.test")

        assertTrue(CallRecordingEventListener.incompatible)
        assertEquals(
            "OkHttp version is incompatible with the SDK. New calls are not observed",
            logs.single().first
        )
    }

    @Test
    fun `other errors reach the call`() {
        val listener = listener { throw AssertionError() }

        assertFailsWith<AssertionError> { listener.dnsStart(call(OkHttpClient()), "example.test") }
    }

    @Test
    fun `a canceled call is canceled`() {
        val call = call(OkHttpClient())
        val listener = listener { 0 }
        listener.dnsStart(call, "example.test")

        call.cancel()
        listener.callFailed(call, IOException("Canceled"))

        assertEquals(ExchangeOutcome.Canceled, published.single().outcome)
    }

    @Test
    fun `a call timeout is a failure, not a cancel`() {
        val call = call(OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build())
        val listener = listener { 0 }
        listener.dnsStart(call, "example.test")

        call.cancel()
        listener.callFailed(call, InterruptedIOException("timeout"))

        val outcome = assertIs<ExchangeOutcome.Failed>(published.single().outcome)
        assertEquals(FailureType.TIMED_OUT, outcome.type)
    }

    @Test
    fun `a connect failing on a canceled call is canceled, an earlier one is a failure`() {
        val call = call(OkHttpClient())
        var now = 0L
        val listener = listener { now++ }

        listener.connectStart(call, FIRST, Proxy.NO_PROXY)
        listener.connectFailed(call, FIRST, Proxy.NO_PROXY, null, ConnectException("refused"))
        listener.connectStart(call, SECOND, Proxy.NO_PROXY)
        call.cancel()
        listener.connectFailed(call, SECOND, Proxy.NO_PROXY, null, SocketException("Socket closed"))
        listener.callFailed(call, IOException("Canceled"))

        val (refused, canceled) = published.single().attempts
        assertIs<ConnectResult.Failed>(refused.result)
        assertEquals(ConnectResult.Canceled, canceled.result)
    }

    @Test
    fun `a connect OkHttp dropped after another connected is superseded`() {
        val call = call(OkHttpClient())
        var now = 0L
        val listener = listener { now++ }

        listener.connectStart(call, FIRST, Proxy.NO_PROXY)
        listener.connectStart(call, SECOND, Proxy.NO_PROXY)
        listener.connectEnd(call, SECOND, Proxy.NO_PROXY, null)
        listener.connectFailed(call, FIRST, Proxy.NO_PROXY, null, IOException("canceled"))
        listener.callEnd(call)

        assertEquals(ConnectResult.Superseded, published.single().attempts.first().result)
    }

    @Test
    fun `a socket timeout is not the call timeout`() {
        val call = call(OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build())
        val closed = SocketException("Socket closed")
        val listener = listener { 0 }
        listener.requestHeadersStart(call)
        listener.requestFailed(call, closed)

        listener.callFailed(call, SocketTimeoutException("timeout"))

        assertEquals(
            FailureType.OTHER,
            assertIs<ExchangeOutcome.Failed>(published.single().outcome).type
        )
    }

    @Test
    fun `a protocol failure after a response rejects it`() {
        val call = call(OkHttpClient())
        val loop = ProtocolException("Too many follow-up requests: 21")
        val listener = listener { 0 }
        listener.requestHeadersStart(call)
        listener.responseHeadersEnd(call, response(call, 302))

        listener.callFailed(call, loop)

        assertEquals(
            FailureType.PROTOCOL_ERROR,
            assertIs<ExchangeOutcome.Failed>(published.single().outcome).type
        )
    }

    @Test
    fun `a failure on the handshake's thread is the handshake's, another thread's is not`() {
        val call = call(OkHttpClient())
        var now = 0L
        val listener = listener { now++ }
        val reset = SocketException("Connection reset")

        listener.connectStart(call, FIRST, Proxy.NO_PROXY)
        listener.connectStart(call, SECOND, Proxy.NO_PROXY)
        listener.secureConnectStart(call)
        thread {
            listener.connectFailed(
                call,
                SECOND,
                Proxy.NO_PROXY,
                null,
                SocketException("Socket closed")
            )
        }.join()
        listener.connectFailed(call, FIRST, Proxy.NO_PROXY, null, reset)
        listener.callFailed(call, reset)

        val (winner, loser) = published.single().attempts
        assertEquals(ConnectResult.Failed(ExchangeStep.TLS, FailureType.RESET), winner.result)
        assertEquals(ConnectResult.Superseded, loser.result)
    }

    @Test
    fun `a refused racing connect is a failure, not a dropped one`() {
        val call = call(OkHttpClient())
        var now = 0L
        val listener = listener { now++ }
        val refused = ConnectException("Connection refused")

        listener.connectStart(call, FIRST, Proxy.NO_PROXY)
        listener.connectStart(call, SECOND, Proxy.NO_PROXY)
        listener.connectEnd(call, SECOND, Proxy.NO_PROXY, null)
        listener.connectFailed(call, FIRST, Proxy.NO_PROXY, null, refused)
        listener.callEnd(call)

        assertEquals(
            ConnectResult.Failed(ExchangeStep.TCP, FailureType.REFUSED),
            published.single().attempts.first().result
        )
    }

    private fun listener(clock: MonotonicClock) =
        CallRecordingEventListener(CallRecorder(clock, OkHttpFailureMapper(), published::add))

    private fun call(client: OkHttpClient): Call =
        client.newCall(Request.Builder().url("http://example.test/").build())

    private fun response(call: Call, code: Int): Response = Response.Builder()
        .request(call.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .build()

    private companion object {
        val FIRST = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)), 80)
        val SECOND = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)), 80)
    }
}
