package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.core.time.MonotonicClock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

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
        assertIs<InterruptedIOException>(outcome.cause)
    }

    private fun listener(clock: MonotonicClock) =
        CallRecordingEventListener(CallRecorder(clock, published::add))

    private fun call(client: OkHttpClient): Call =
        client.newCall(Request.Builder().url("http://example.test/").build())
}
