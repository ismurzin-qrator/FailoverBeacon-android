package net.qrator.failoverbeacon.telemetry

import android.util.Log
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.logging.LogOutput

class TelemetryCollectorTest {
    private val logs = mutableListOf<Pair<Int, String>>()
    private val previousOutput = SdkRegistry.logger.output
    private val previousLevel = SdkRegistry.logger.minLevel
    private val consentSource = TestConsentSource(TrackingConsent.GRANTED)
    private val tasks = ArrayDeque<Runnable>()
    private val collector = TelemetryCollector(
        consentSource,
        blockedHosts = setOf("blocked.com"),
        failureSampleRate = 0.7,
        successSampleRate = 0.3,
        executor = tasks::addLast
    )

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.output = LogOutput { level, message, _ -> logs += level to message }
        SdkRegistry.logger.minLevel = Log.DEBUG
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.logger.minLevel = previousLevel
    }

    @Test
    fun `logs a kept event with the fraction of its class`() {
        send(exchange(callId = KEPT))
        send(failure())

        val (level, message) = logs.first()
        assertEquals(Log.DEBUG, level)
        assertTrue(message.startsWith("Telemetry event TelemetryEvent(callId=$KEPT"), message)
        assertEquals(
            listOf("samplingFraction=0.3", "samplingFraction=0.7"),
            logs.map { it.second.substringAfterLast(", ") }
        )
    }

    @Test
    fun `collects with consent granted or pending, never without it`() {
        send(failure())
        consentSource.consent = TrackingConsent.PENDING
        send(failure())
        consentSource.consent = TrackingConsent.NOT_GRANTED
        send(failure())

        assertEquals(2, logs.size)
    }

    @Test
    fun `reads consent when the SDK thread gets to the event`() {
        collector.onExchange(failure())
        consentSource.consent = TrackingConsent.NOT_GRANTED
        tasks.removeFirst().run()

        assertEquals(emptyList(), logs)
    }

    @Test
    fun `drops a blocked host only, subdomains and unnamed follow-ups pass`() {
        send(failure(host = "blocked.com"))
        send(failure(host = "sub.blocked.com"))
        send(failure(host = null))

        assertEquals(2, logs.size)
    }

    @Test
    fun `drops what the sampler drops`() {
        send(exchange(callId = DROPPED))

        assertEquals(emptyList(), logs)
    }

    private fun send(exchange: Exchange) {
        collector.onExchange(exchange)
        tasks.removeFirst().run()
    }

    private fun failure(host: String? = "a.com") = exchange(
        callId = KEPT,
        request = ExchangeRequest("https", host, 443, "GET", bodyBytes = null),
        response = ExchangeResponse(500, null, 0)
    )

    private companion object {
        const val KEPT = 0L
        const val DROPPED = -1L
    }
}
