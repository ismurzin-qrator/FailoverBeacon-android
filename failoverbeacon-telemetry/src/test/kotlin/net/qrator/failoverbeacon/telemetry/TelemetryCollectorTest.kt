package net.qrator.failoverbeacon.telemetry

import android.util.Log
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.logging.LogOutput

class TelemetryCollectorTest {
    private val logs = mutableListOf<Pair<Int, String>>()
    private val previousOutput = SdkRegistry.logger.output
    private val previousLevel = SdkRegistry.logger.minLevel
    private val consentSource = TestConsentSource(TrackingConsent.GRANTED)
    private val collector = TelemetryCollector(
        consentSource,
        blockedHosts = setOf("blocked.com"),
        failureSampleRate = 1.0,
        successSampleRate = 0.0
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
    fun `logs the event of a kept exchange with its sampling fraction`() {
        collector.onExchange(failure())

        val (level, message) = logs.single()
        assertEquals(Log.DEBUG, level)
        assertTrue(message.startsWith("Telemetry event TelemetryEvent(callId=7"), message)
        assertContains(message, "samplingFraction=1.0")
    }

    @Test
    fun `drops an exchange the filter or the sampler drops`() {
        collector.onExchange(failure(host = "blocked.com"))
        collector.onExchange(exchange())
        consentSource.consent = TrackingConsent.NOT_GRANTED
        collector.onExchange(failure())

        assertTrue(logs.isEmpty())
    }

    private fun failure(host: String = "a.com") = exchange(
        request = ExchangeRequest(host, 443, "GET", bodyBytes = null),
        response = ExchangeResponse(500, null, 0)
    )
}
