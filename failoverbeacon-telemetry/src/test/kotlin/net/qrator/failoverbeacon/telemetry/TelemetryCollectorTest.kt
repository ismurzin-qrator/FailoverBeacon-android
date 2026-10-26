package net.qrator.failoverbeacon.telemetry

import android.util.Log
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.ConsentSource
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
    private val collector = newCollector(consentSource)

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
    fun `stores a kept event and logs it`() {
        send(failure())

        val line = storedLines().single()
        assertEquals("a.com", JSONObject(line).getString("host"))
        assertEquals(
            listOf(Log.DEBUG to "[Telemetry] Event $line"),
            logs.filter { it.second.startsWith("[Telemetry] Event ") }
        )
        assertTrue(
            logs.indexOfFirst { it.second.startsWith("[Telemetry] Event ") } <
                logs.indexOfFirst { it.second.startsWith("[Storage] Event stored: ") }
        )
    }

    @Test
    fun `drops stored events beyond the memory queue size and logs it once`() {
        repeat(4) { collector.onExchange(failure()) }
        collector.onExchange(exchange(callId = DROPPED))
        collector.onExchange(failure(host = "blocked.com"))
        val queuedWhenFull = tasks.size
        tasks.removeFirst().run()
        collector.onExchange(failure())

        assertEquals(2, queuedWhenFull)
        assertEquals(2, tasks.size)
        assertEquals(1, logs.count { it.first == Log.WARN })
        assertEquals(mapOf(DropReason.MEMORY_OVERFLOW to 2L), dropped.snapshot(BEACON))
        assertEquals(
            2,
            logs.count {
                it.second ==
                    "[Telemetry] Events dropped: beacons=all, reason=memory_overflow, count=1"
            }
        )
    }

    @Test
    fun `a data deletion is queued while the memory queue is full`() {
        repeat(2) { collector.onExchange(failure()) }

        collector.clearData()

        assertEquals(3, tasks.size)
    }

    @Test
    fun `a failed event frees its place in the memory queue`() {
        var reads = 0
        val collector = newCollector(
            object : ConsentSource {
                override val consent: TrackingConsent
                    get() {
                        check(reads++ != 1) { "consent unreadable" }
                        return TrackingConsent.GRANTED
                    }
            }
        )
        collector.onExchange(failure())
        assertFailsWith<IllegalStateException> { tasks.removeFirst().run() }

        repeat(2) { collector.onExchange(failure()) }

        assertEquals(2, tasks.size)
    }

    @Test
    fun `clearData clears the stored events and the drop counts on the SDK thread`() {
        send(failure())
        dropped.add(BEACON, DropReason.EXPIRED, 1)
        val stored = storedLines().size

        collector.clearData()
        val storedUntilTheTaskRuns = storedLines().size
        tasks.removeFirst().run()

        assertEquals(listOf(1, 1, 0), listOf(stored, storedUntilTheTaskRuns, storedLines().size))
        assertEquals(emptyMap(), dropped.snapshot(BEACON))
    }

    @Test
    fun `stores the sampling fraction of the event's class`() {
        send(exchange(callId = KEPT))
        send(failure())

        assertEquals(
            listOf(0.3, 0.7),
            storedLines().map { JSONObject(it).getDouble("sampling_fraction") }
        )
    }

    @Test
    fun `collects with consent granted or pending, without it queues nothing`() {
        send(failure())
        consentSource.consent = TrackingConsent.PENDING
        send(failure())
        consentSource.consent = TrackingConsent.NOT_GRANTED
        collector.onExchange(failure())

        assertEquals(0, tasks.size)
        assertEquals(2, storedLines().size)
    }

    @Test
    fun `reads consent again when the SDK thread gets to the event`() {
        send(failure())
        collector.onExchange(failure())
        consentSource.consent = TrackingConsent.NOT_GRANTED
        tasks.removeFirst().run()

        assertEquals(1, storedLines().size)
    }

    @Test
    fun `drops a blocked host only, subdomains and unnamed follow-ups pass`() {
        send(failure(host = "blocked.com"))
        send(failure(host = "sub.blocked.com"))
        send(failure(host = null))

        assertEquals(
            listOf("sub.blocked.com", null),
            storedLines().map { JSONObject(it).optString("host").ifEmpty { null } }
        )
    }

    @Test
    fun `stores nothing the sampler drops`() {
        send(exchange(callId = DROPPED))
        send(failure())

        assertEquals(1, storedLines().size)
    }

    private fun newCollector(consentSource: ConsentSource) = TelemetryCollector(
        consentSource,
        blockedHosts = setOf("blocked.com"),
        failureSampleRate = 0.7,
        successSampleRate = 0.3,
        memoryQueueSize = 2,
        executor = tasks::addLast,
        storage = storage,
        dropped = dropped
    )

    private fun storedDir() = temp.root.resolve("failoverbeacon")

    private fun storedLines(): List<String> = storedDir().walk()
        .filter { it.isFile }
        .sortedBy { it.name }
        .flatMap { it.readLines() }
        .toList()

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
