package net.qrator.failoverbeacon.telemetry.upload

import android.net.TrafficStats
import android.os.Build
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
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.core.network.NetworkSource
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.core.network.model.NetworkTransport
import net.qrator.failoverbeacon.telemetry.BuildConfig
import net.qrator.failoverbeacon.telemetry.DropReason
import net.qrator.failoverbeacon.telemetry.DroppedEvents
import net.qrator.failoverbeacon.telemetry.TestConsentSource
import net.qrator.failoverbeacon.telemetry.storage.EventStorage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
    private var network: NetworkSnapshot = NetworkSnapshot.Unknown
    private val dropped = DroppedEvents(listOf(beacon))
    private val storage = storage(listOf(beacon))
    private val uploader = uploader(listOf(beacon), storage)
    private val previousOutput = SdkRegistry.logger.output
    private val previousLevel = SdkRegistry.logger.minLevel
    private val warnings = CopyOnWriteArrayList<String>()
    private val debug = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.minLevel = Log.WARN
        SdkRegistry.logger.output = LogOutput { level, message, _ ->
            when (level) {
                Log.WARN -> warnings += message
                Log.DEBUG -> debug += message
            }
        }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.logger.minLevel = previousLevel
        SdkRegistry.clear()
    }

    @Test
    fun `sends ready batches in one request after a metadata line and deletes them`() {
        dropped.add(beacon, DropReason.EXPIRED, 40)
        writeInNewBatches("one", "two")
        respond(202)

        uploader.upload()

        val metadata = """{"sent_at":$now,"sdk_version":"${BuildConfig.SDK_VERSION}",""" +
            """"schema_version":"1.0.0","app_package":"com.example.app","app_version":"2.1",""" +
            """"os_api_level":${Build.VERSION.SDK_INT},"dropped":{"expired":40}}"""
        assertEquals(listOf(listOf(metadata, "one", "two")), requests().map { it.lines })
        assertEquals(emptyList(), storedFiles())
        assertEquals(emptyMap(), dropped.snapshot(beacon))
        assertEquals(emptyList(), debug)
    }

    @Test
    fun `reads the app version once`() {
        var reads = 0
        val uploader = uploader(listOf(beacon), storage, appVersion = {
            reads++
            null
        })
        writeInNewBatches("one")
        respond(202)
        uploader.upload()
        writeInNewBatches("two")
        respond(202)

        uploader.upload()

        assertEquals(1, reads)
        assertEquals(List(2) { false }, requests().map { "app_version" in it.lines.first() })
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
    fun `sends nothing without network or with network blocked, and sends once it is back`() {
        writeInNewBatches("one")
        respond(202)

        network = NetworkSnapshot.None
        uploader.upload()
        network = connected(blocked = true)
        uploader.upload()
        assertEquals(0, server.requestCount)
        assertEquals(1, storedFiles().size)

        network = connected(blocked = false)
        uploader.upload()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `sends when the network or its blocking is unknown`() {
        respond(202, 202)

        listOf(NetworkSnapshot.Unknown, connected(blocked = null)).forEach {
            network = it
            writeInNewBatches("one")
            uploader.upload()
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a network lost between requests stops the pass`() {
        val networks = ArrayDeque(listOf(NetworkSnapshot.Unknown))
        val uploader = uploader(listOf(beacon), storage, networkSource = {
            networks.removeFirstOrNull() ?: NetworkSnapshot.None
        })
        writeInNewBatches(*Array(3) { "1".repeat(400 * 1024) })
        respond(202)

        uploader.upload()

        assertEquals(1, server.requestCount)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun `a timeout, a rate limit or a server error keeps the batches`() {
        writeInNewBatches("one")
        val codes = intArrayOf(408, 429, 500, 503, 599)
        respond(*codes)

        codes.forEach { _ -> uploader(listOf(beacon), storage).upload() }

        assertEquals(codes.size, server.requestCount)
        assertEquals(1, storedFiles().size)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a dropped connection or an unreachable beacon keeps the batches`() {
        writeInNewBatches("one")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        uploader.upload()
        server.shutdown()
        uploader.upload()

        assertEquals(1, server.requestCount)
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
        SdkRegistry.logger.minLevel = Log.DEBUG
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
                "[Upload] Beacon ${server.hostName} rejected events with code 400",
                "[Upload] Beacon ${server.hostName} rejected events with code 307"
            ),
            warnings
        )
        assertEquals(
            1,
            (debug + warnings).count {
                it.contains("code 400") ||
                    it.contains("code=400")
            }
        )
        assertEquals(
            3,
            debug.count {
                it.startsWith("[Storage] Batch deletion:") &&
                    it.endsWith("deleted=true")
            }
        )
    }

    @Test
    fun `a gone beacon gets nothing more`() {
        SdkRegistry.logger.minLevel = Log.DEBUG
        writeInNewBatches("one")
        val directory = assertNotNull(storedFiles().single().parentFile).name
        respond(410)

        uploader.upload()
        writeInNewBatches("two")
        uploader.upload()

        assertEquals(1, server.requestCount)
        assertEquals(emptyList(), storedFiles())
        assertEquals(
            listOf("[Upload] Beacon ${server.hostName} is gone. Events are no longer sent to it"),
            warnings
        )
        assertTrue(
            debug.contains(
                "[Storage] Directory removed: directory=$directory, deleted=true"
            )
        )
    }

    @Test
    fun `a failing beacon does not hold back the next one`() {
        val other = MockWebServer()
        val beacons = listOf(beacon, other.url("/events").toString())
        val storage = storage(beacons)
        val uploader = uploader(beacons, storage)
        respond(500, 500)
        try {
            listOf("one", "two", "three").forEach { line ->
                writeInNewBatches(line, storage = storage)
                other.enqueue(MockResponse().setResponseCode(202))
                uploader.upload()
            }

            assertEquals(2, server.requestCount)
            assertEquals(listOf("one", "two", "three"), requests(other).flatMap { it.events })
            assertEquals(3, storedFiles().size)
        } finally {
            other.shutdown()
        }
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
    fun `a batch over 1 MB goes alone`() {
        val large = "1".repeat(1100 * 1024)
        writeInNewBatches(large, "two")
        respond(202, 202)

        uploader.upload()

        assertEquals(listOf(listOf(large), listOf("two")), requests().map { it.events })
    }

    @Test
    fun `tags the sockets of the upload thread`() {
        try {
            uploader.upload()

            assertEquals(SDK_TRAFFIC_TAG, TrafficStats.getThreadStatsTag())
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
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
        SdkRegistry.logger.minLevel = Log.DEBUG
        writeInNewBatches("one")

        Thread.currentThread().interrupt()
        try {
            uploader.upload()
        } finally {
            Thread.interrupted()
        }

        assertEquals(0, server.requestCount)
        assertTrue(
            debug.contains("[Upload] Paused: reason=interrupted")
        )
    }

    @Test
    fun `debug links stored files and reported losses to their upload and deletion`() {
        writeInNewBatches("one", "two")
        val files = storedFiles().sortedBy {
            it.name
        }.map { "${assertNotNull(it.parentFile).name}/${it.name}" }
        dropped.add(beacon, DropReason.EXPIRED, 3)
        respond(202)
        SdkRegistry.logger.minLevel = Log.DEBUG

        uploader.upload()

        assertTrue(
            debug.contains(
                "[Upload] Batch: host=${server.hostName}, files=$files, events=2, reported_dropped={expired=3}"
            )
        )
        assertEquals(
            2,
            debug.count {
                it.startsWith("[Storage] Batch deletion:") &&
                    it.endsWith("deleted=true")
            }
        )
        assertEquals(
            listOf("[Upload] Accepted: host=${server.hostName}, code=202"),
            debug.filter {
                it.contains("code=")
            }
        )
        assertEquals(emptyList(), storedFiles())
        assertEquals(listOf("one", "two"), requests().single().events)
        assertTrue(debug.none { it.contains("/events") })
    }

    @Test
    fun `debug explains empty queue, network, consent and retry waits`() {
        SdkRegistry.logger.minLevel = Log.DEBUG
        uploader.upload()
        writeInNewBatches("one")
        network = NetworkSnapshot.None
        uploader.upload()
        network = NetworkSnapshot.Unknown
        consent.consent = TrackingConsent.PENDING
        uploader.upload()
        consent.consent = TrackingConsent.GRANTED
        respond(503, 503)

        repeat(3) { uploader.upload() }

        for (reason in listOf("no_ready_batches", "backoff")) {
            assertTrue(debug.contains("[Upload] Skipped: host=${server.hostName}, reason=$reason"))
        }
        for (reason in listOf("network_unavailable", "consent")) {
            assertTrue(debug.contains("[Upload] Paused: reason=$reason"))
        }
        assertEquals(
            2,
            debug.count {
                it == "[Upload] Retained: host=${server.hostName}, code=503"
            }
        )
        assertEquals(2, debug.count { it.contains("code=") })
        assertEquals(2, server.requestCount)
        assertEquals(1, storedFiles().size)
        assertTrue(debug.none { it.startsWith("[Storage] Batch deletion:") })
    }

    @Test
    fun `debug distinguishes two beacon paths on the same host`() {
        val beacons = listOf(beacon, server.url("/other?token=private").toString())
        val storage = storage(beacons)
        val uploader = uploader(beacons, storage)
        writeInNewBatches("one", storage = storage)
        val files = storedFiles().map { "${assertNotNull(it.parentFile).name}/${it.name}" }
        respond(202, 202)
        SdkRegistry.logger.minLevel = Log.DEBUG

        uploader.upload()

        assertEquals(2, server.requestCount)
        for (file in files) {
            assertEquals(1, debug.count { it.startsWith("[Upload] Batch:") && it.contains(file) })
        }
        assertEquals(
            listOf("/events", "/other?token=private"),
            List(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path }
        )
        assertTrue(
            debug.none {
                it.contains("/events") || it.contains("other") ||
                    it.contains("private")
            }
        )
    }

    private fun storage(beacons: List<String>) =
        EventStorage({ temp.root }, beacons, { now }, WINDOW, DISK_BYTES, dropped)

    private fun uploader(
        beacons: List<String>,
        storage: EventStorage,
        networkSource: NetworkSource = NetworkSource { network },
        appVersion: () -> String? = { "2.1" }
    ) = TelemetryUploader(
        beacons,
        storage,
        Executor(Runnable::run),
        consent,
        networkSource,
        { now },
        dropped,
        "com.example.app",
        appVersion
    )

    private fun connected(blocked: Boolean?) = NetworkSnapshot.Connected(
        handle = 1,
        transport = NetworkTransport.WIFI,
        vpn = false,
        validated = true,
        captivePortal = false,
        metered = false,
        roaming = false,
        downstreamKbps = null,
        upstreamKbps = null,
        signalStrength = null,
        carrier = null,
        blocked = blocked
    )

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
        const val SDK_TRAFFIC_TAG = 0x4642
    }
}
