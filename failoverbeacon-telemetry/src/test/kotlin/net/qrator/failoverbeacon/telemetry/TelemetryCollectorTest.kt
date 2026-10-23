package net.qrator.failoverbeacon.telemetry

import android.util.Log
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.telemetry.storage.EventStorage
import org.json.JSONObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryCollectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val logs = mutableListOf<Pair<Int, String>>()
    private val previousOutput = SdkRegistry.logger.output
    private val previousLevel = SdkRegistry.logger.minLevel
    private val consentSource = TestConsentSource(TrackingConsent.GRANTED)
    private val tasks = ArrayDeque<Runnable>()
    private val dropped = DroppedEvents(listOf(BEACON))
    private val storage = EventStorage(
        cacheDir = { temp.root },
        beacons = listOf(BEACON),
        clock = { 0 },
        batchWindowMillis = 60_000,
        maxBytes = 1_000_000,
        dropped = dropped
    )
    private val collector = TelemetryCollector(
        consentSource,
        blockedHosts = setOf("blocked.com"),
        failureSampleRate = 0.7,
        successSampleRate = 0.3,
        memoryQueueSize = 2,
        executor = tasks::addLast,
        storage = storage,
        dropped = dropped
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
    fun `stores a kept event`() {
        send(failure())

        val batch = storedDir().walk().single { it.isFile }
        assertEquals("a.com", JSONObject(batch.readLines().single()).getString("host"))
    }

    @Test
    fun `withdrawn consent clears the stored events on the SDK thread, others keep them`() {
        send(failure())

        collector.onConsentChanged(TrackingConsent.PENDING)
        collector.onConsentChanged(TrackingConsent.GRANTED)
        val queuedBeforeWithdrawal = tasks.size
        collector.onConsentChanged(TrackingConsent.NOT_GRANTED)
        val stored = storedDir().exists()
        tasks.removeFirst().run()

        assertEquals(0, queuedBeforeWithdrawal)
        assertTrue(stored)
        assertFalse(storedDir().exists())
    }

    @Test
    fun `an exchange that ends without consent is not queued`() {
        consentSource.consent = TrackingConsent.NOT_GRANTED
        collector.onExchange(failure())
        consentSource.consent = TrackingConsent.GRANTED

        assertEquals(0, tasks.size)
    }

    @Test
    fun `drops events beyond the memory queue size and logs it once`() {
        repeat(4) { collector.onExchange(failure()) }
        val queuedWhenFull = tasks.size
        tasks.removeFirst().run()
        collector.onExchange(failure())

        assertEquals(2, queuedWhenFull)
        assertEquals(2, tasks.size)
        assertEquals(1, logs.count { it.first == Log.WARN })
        assertEquals(mapOf(DropReason.MEMORY_OVERFLOW to 2L), dropped.snapshot(BEACON))
    }

    @Test
    fun `a data deletion is queued while the memory queue is full`() {
        repeat(2) { collector.onExchange(failure()) }

        collector.clearData()

        assertEquals(3, tasks.size)
    }

    @Test
    fun `clearData clears the stored events and the drop counts on the SDK thread`() {
        send(failure())
        dropped.add(BEACON, DropReason.EXPIRED, 1)

        collector.clearData()
        tasks.removeFirst().run()

        assertFalse(storedDir().exists())
        assertEquals(emptyMap(), dropped.snapshot(BEACON))
    }

    @Test
    fun `logs a kept event with the fraction of its class`() {
        send(exchange(callId = KEPT))
        send(failure())

        val (level, message) = logs.first()
        assertEquals(Log.DEBUG, level)
        assertTrue(message.startsWith("""Telemetry event {"format_version":1,"""), message)
        assertEquals(
            listOf(0.3, 0.7),
            logs.map {
                JSONObject(it.second.substringAfter(" event ")).getDouble("sampling_fraction")
            }
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

    private fun storedDir() = temp.root.resolve("failoverbeacon")

    private fun send(exchange: Exchange) {
        collector.onExchange(exchange)
        tasks.removeFirstOrNull()?.run()
    }

    private fun failure(host: String? = "a.com") = exchange(
        callId = KEPT,
        request = ExchangeRequest("https", host, 443, "GET", bodyBytes = null),
        response = ExchangeResponse(500, null, 0)
    )

    private companion object {
        const val BEACON = "https://beacon.example"
        const val KEPT = 0L
        const val DROPPED = -1L
    }
}
