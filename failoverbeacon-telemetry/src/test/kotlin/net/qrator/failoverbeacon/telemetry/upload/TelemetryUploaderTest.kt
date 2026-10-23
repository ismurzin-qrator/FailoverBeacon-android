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
import net.qrator.failoverbeacon.telemetry.TestConsentSource
import net.qrator.failoverbeacon.telemetry.storage.EventStorage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryUploaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val a = MockWebServer()
    private val b = MockWebServer()
    private val beacons = listOf(a.url("/a").toString(), b.url("/b").toString())
    private val consent = TestConsentSource(TrackingConsent.GRANTED)
    private var now = START
    private val storage = EventStorage({ temp.root }, beacons, { now }, WINDOW, 5L * 1024 * 1024)
    private val uploader =
        TelemetryUploader(beacons, storage, Executor(Runnable::run), consent) { now }
    private val previousOutput = SdkRegistry.logger.output
    private val warnings = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.output =
            LogOutput { level, message, _ -> if (level == Log.WARN) warnings += message }
    }

    @AfterTest
    fun tearDown() {
        a.shutdown()
        b.shutdown()
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.clear()
    }

    @Test
    fun `sends ready batches in one request after a metadata line and deletes them`() {
        writeInNewBatches("one", "two")
        a.enqueue(MockResponse().setResponseCode(202))
        b.enqueue(MockResponse().setResponseCode(202))

        uploader.upload()

        val metadata = """{"sent_at":$now,"sdk_version":"${BuildConfig.SDK_VERSION}"}"""
        assertEquals(listOf(metadata, "one", "two"), lines(a))
        assertEquals(listOf(metadata, "one", "two"), lines(b))
        assertEquals(emptyList(), storedFiles())
    }

    @Test
    fun `sends nothing unless consent is granted`() {
        writeInNewBatches("one")

        consent.consent = TrackingConsent.PENDING
        uploader.upload()
        consent.consent = TrackingConsent.NOT_GRANTED
        uploader.upload()

        assertEquals(0, a.requestCount + b.requestCount)
        assertEquals(2, storedFiles().size)
    }

    @Test
    fun `a failed request keeps its batches and the next beacon is still sent`() {
        writeInNewBatches("one")
        a.enqueue(MockResponse().setResponseCode(500))
        a.enqueue(MockResponse().setResponseCode(202))
        b.enqueue(MockResponse().setResponseCode(202))

        uploader.upload()
        val keptAfterFailure = storedFiles().size
        uploader.upload()

        assertEquals(1, keptAfterFailure)
        assertEquals(listOf("one"), lines(a).drop(1))
        assertEquals(listOf("one"), lines(a).drop(1))
        assertEquals(1, b.requestCount)
        assertEquals(emptyList(), storedFiles())
    }

    @Test
    fun `a gone beacon gets nothing more until the SDK starts again`() {
        writeInNewBatches("one")
        a.enqueue(MockResponse().setResponseCode(410))
        b.enqueue(MockResponse().setResponseCode(202))
        b.enqueue(MockResponse().setResponseCode(202))

        uploader.upload()
        val storedAfterGone = storedFiles()
        writeInNewBatches("two")
        uploader.upload()

        assertEquals(emptyList(), storedAfterGone)
        assertEquals(1, a.requestCount)
        assertEquals(listOf("one"), lines(b).drop(1))
        assertEquals(listOf("two"), lines(b).drop(1))
        assertEquals(
            listOf("Beacon ${beacons[0]} is gone. Events are no longer sent to it"),
            warnings
        )
    }

    @Test
    fun `other codes drop the batches with one warning per beacon and code`() {
        writeInNewBatches("one")
        a.enqueue(MockResponse().setResponseCode(400))
        b.enqueue(MockResponse().setResponseCode(307))
        a.enqueue(MockResponse().setResponseCode(400))
        b.enqueue(MockResponse().setResponseCode(202))

        uploader.upload()
        val storedAfterRejected = storedFiles()
        writeInNewBatches("two")
        uploader.upload()

        assertEquals(emptyList(), storedAfterRejected)
        assertEquals(emptyList(), storedFiles())
        assertEquals(
            listOf(
                "Beacon ${beacons[0]} rejected events with code 400. Events are dropped",
                "Beacon ${beacons[1]} rejected events with code 307. Events are dropped"
            ),
            warnings
        )
    }

    @Test
    fun `a timeout, a rate limit and server errors keep the batches`() {
        writeInNewBatches("one")
        b.enqueue(MockResponse().setResponseCode(202))
        val codes = listOf(408, 429, 500, 503, 599)

        codes.forEach { code ->
            a.enqueue(MockResponse().setResponseCode(code))
            uploader.upload()
        }

        assertEquals(codes.size, a.requestCount)
        assertEquals(1, storedFiles().size)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `batches over 1 MB go in several requests of one pass, oldest first`() {
        val single = listOf(beacons.first())
        val storage = EventStorage({ temp.root }, single, { now }, WINDOW, 5L * 1024 * 1024)
        val uploader = TelemetryUploader(single, storage, Executor(Runnable::run), consent) { now }
        val (first, second, third) = listOf("1", "2", "3").map { it.repeat(400 * 1024) }
        writeInNewBatches(first, second, third, storage = storage)
        repeat(2) { a.enqueue(MockResponse().setResponseCode(202)) }

        uploader.upload()

        assertEquals(listOf(first, second), lines(a).drop(1))
        assertEquals(listOf(third), lines(a).drop(1))
        assertEquals(emptyList(), storedFiles())
    }

    @Test
    fun `a batch still taking events waits for a later pass`() {
        storage.write("one")
        now = START + WINDOW / 100 * 105 - 1

        uploader.upload()

        assertEquals(0, a.requestCount + b.requestCount)
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

        assertEquals(0, a.requestCount + b.requestCount)
    }

    private fun writeInNewBatches(vararg lines: String, storage: EventStorage = this.storage) {
        lines.forEach { line ->
            storage.write(line)
            now += WINDOW
        }
        now += WINDOW
    }

    private fun lines(server: MockWebServer): List<String> {
        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        return GZIPInputStream(request.body.inputStream()).reader().readLines()
    }

    private fun storedFiles(): List<File> =
        File(temp.root, "failoverbeacon").walk().filter { it.isFile }.toList()

    private companion object {
        const val START = 1_761_040_980_000L
        const val WINDOW = 60_000L
    }
}
