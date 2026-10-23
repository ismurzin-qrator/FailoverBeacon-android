package net.qrator.failoverbeacon.telemetry.upload

import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.telemetry.BuildConfig
import net.qrator.failoverbeacon.telemetry.DropReason
import net.qrator.failoverbeacon.telemetry.DroppedEvents
import net.qrator.failoverbeacon.telemetry.TestConsentSource
import net.qrator.failoverbeacon.telemetry.storage.EventStorage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryUploaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val beacon = server.url("/events").toString()
    private val consent = TestConsentSource(TrackingConsent.GRANTED)
    private var now = START
    private val dropped = DroppedEvents(listOf(beacon))
    private val storage = storage(listOf(beacon))
    private val uploader = uploader(listOf(beacon), storage)
    private val previousOutput = SdkRegistry.logger.output
    private val warnings = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.output =
            LogOutput { level, message, _ -> if (level == Log.WARN) warnings += message }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.clear()
    }

    @Test
    fun `sends ready batches in one request after a metadata line and deletes them`() {
        dropped.add(beacon, DropReason.EXPIRED, 40)
        writeInNewBatches("one", "two")
        respond(202)

        uploader.upload()

        val metadata = """{"sent_at":$now,"sdk_version":"${BuildConfig.SDK_VERSION}",""" +
            """"dropped":{"expired":40}}"""
        assertEquals(listOf(listOf(metadata, "one", "two")), requests().map { it.lines })
        assertEquals(emptyList(), storedFiles())
        assertEquals(emptyMap(), dropped.snapshot(beacon))
    }

    @Test
    fun `sends nothing unless consent is granted`() {
        writeInNewBatches("one")

        consent.consent = TrackingConsent.PENDING
        uploader.upload()
        consent.consent = TrackingConsent.NOT_GRANTED
        uploader.upload()

        assertEquals(0, server.requestCount)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun `a timeout, a rate limit, a server error or no network keep the batches and back off`() {
        writeInNewBatches("one")
        val codes = listOf(408, 429, 500, 503, 599)

        codes.forEach { code ->
            respond(code, code)
            val uploader = uploader(listOf(beacon), storage)
            repeat(3) { uploader.upload() }
        }
        server.shutdown()
        uploader.upload()

        assertEquals(codes.size * 2, server.requestCount)
        assertEquals(1, storedFiles().size)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a failing beacon waits twice as many passes after each failure, up to 60`() {
        writeInNewBatches("one")
        respond(*IntArray(9) { 500 })

        val attempts = (1..184).filter { uploadSends() }

        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 124, 184), attempts)
    }

    @Test
    fun `a success resets the backoff`() {
        writeInNewBatches("one")
        respond(500, 500, 202, 500, 500)

        val attempts = (1..4).filter { uploadSends() }
        writeInNewBatches("two")

        assertEquals(listOf(1, 2, 4, 5, 6), attempts + (5..6).filter { uploadSends() })
    }

    @Test
    fun `a failed request goes again in the next pass with its drop counts`() {
        dropped.add(beacon, DropReason.DISK_OVERFLOW, 3)
        writeInNewBatches("one")
        respond(500, 202)

        uploader.upload()
        uploader.upload()

        val sent = requests()
        assertEquals(listOf(listOf("one"), listOf("one")), sent.map { it.events })
        assertEquals(List(2) { """{"disk_overflow":3}""" }, sent.map { it.dropped })
        assertEquals(emptyList(), storedFiles())
        assertEquals(emptyMap(), dropped.snapshot(beacon))
    }

    @Test
    fun `rejected events are dropped, logged and counted as send errors`() {
        respond(400, 307, 202)

        listOf("one", "two", "three").forEach { line ->
            writeInNewBatches(line)
            uploader.upload()
        }

        val sent = requests()
        assertEquals(
            listOf(null, """{"send_error":1}""", """{"send_error":2}"""),
            sent.map { it.dropped }
        )
        assertEquals(emptyList(), storedFiles())
        assertEquals(
            listOf(
                "Beacon $beacon rejected events with code 400. Events are dropped",
                "Beacon $beacon rejected events with code 307. Events are dropped"
            ),
            warnings
        )
    }

    @Test
    fun `a gone beacon gets nothing more`() {
        writeInNewBatches("one")
        respond(410)

        uploader.upload()
        writeInNewBatches("two")
        uploader.upload()

        assertEquals(1, server.requestCount)
        assertEquals(emptyList(), storedFiles())
        assertEquals(listOf("Beacon $beacon is gone. Events are no longer sent to it"), warnings)
    }

    @Test
    fun `a failing beacon does not hold back the next one`() {
        val other = MockWebServer()
        val beacons = listOf(beacon, other.url("/events").toString())
        val storage = storage(beacons)
        val uploader = uploader(beacons, storage)
        respond(500, 500)
        listOf("one", "two", "three").forEach { line ->
            writeInNewBatches(line, storage = storage)
            other.enqueue(MockResponse().setResponseCode(202))
            uploader.upload()
        }

        assertEquals(2, server.requestCount)
        assertEquals(listOf("one", "two", "three"), requests(other).flatMap { it.events })
        assertEquals(3, storedFiles().size)
        other.shutdown()
    }

    @Test
    fun `batches over 1 MB go in several requests of one pass, oldest first`() {
        val (first, second, third) = listOf("1", "2", "3").map { it.repeat(400 * 1024) }
        writeInNewBatches(first, second, third)
        respond(202, 202)

        uploader.upload()

        assertEquals(listOf(listOf(first, second), listOf(third)), requests().map { it.events })
        assertEquals(emptyList(), storedFiles())
    }

    @Test
    fun `a batch with no whole line is deleted without a request`() {
        writeInNewBatches("one")
        storedFiles().single().writeText("cut")

        uploader.upload()

        assertEquals(0, server.requestCount)
        assertEquals(emptyList(), storedFiles())
    }

    @Test
    fun `an interrupted thread sends nothing`() {
        writeInNewBatches("one")

        Thread.currentThread().interrupt()
        try {
            uploader.upload()
        } finally {
            Thread.interrupted()
        }

        assertEquals(0, server.requestCount)
    }

    private fun storage(beacons: List<String>) =
        EventStorage({ temp.root }, beacons, { now }, WINDOW, DISK_BYTES, dropped)

    private fun uploader(beacons: List<String>, storage: EventStorage) =
        TelemetryUploader(beacons, storage, Executor(Runnable::run), consent, { now }, dropped)

    private fun writeInNewBatches(vararg lines: String, storage: EventStorage = this.storage) {
        lines.forEach { line ->
            storage.write(line)
            now += WINDOW
        }
        now += WINDOW
    }

    private fun uploadSends(): Boolean {
        val before = server.requestCount
        uploader.upload()
        return server.requestCount > before
    }

    private fun respond(vararg codes: Int) {
        codes.forEach { server.enqueue(MockResponse().setResponseCode(it)) }
    }

    private fun requests(server: MockWebServer = this.server): List<SentRequest> =
        List(server.requestCount) {
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            SentRequest(GZIPInputStream(request.body.inputStream()).reader().readLines())
        }

    private fun storedFiles(): List<File> =
        File(temp.root, "failoverbeacon").walk().filter { it.isFile }.toList()

    private class SentRequest(val lines: List<String>) {
        val events = lines.drop(1)
        val dropped = JSONObject(lines.first()).optJSONObject("dropped")?.toString()
    }

    private companion object {
        const val START = 1_761_040_980_000L
        const val WINDOW = 60_000L
        const val DISK_BYTES = 5L * 1024 * 1024
    }
}
