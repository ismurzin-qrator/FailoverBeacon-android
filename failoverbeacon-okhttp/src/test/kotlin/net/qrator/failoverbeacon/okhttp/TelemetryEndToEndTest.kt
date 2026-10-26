package net.qrator.failoverbeacon.okhttp

import android.util.Log
import java.io.File
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.Consent
import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.FailoverBeaconOptions
import net.qrator.failoverbeacon.core.SdkRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class TelemetryEndToEndTest {
    private val app = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<String>()
    private val client = OkHttpClient.Builder().dns {
        listOf(LOOPBACK)
    }.build().withFailoverBeacon()
    private val previousOutput = SdkRegistry.logger.output
    private val everySecond =
        FailoverBeaconOptions.Builder().uploadPeriod(1, TimeUnit.SECONDS).build()

    @BeforeTest
    fun setUp() {
        server.start(LOOPBACK, 0)
        FailoverBeacon.setLogLevel(Log.DEBUG)
        FailoverBeacon.setLogger { _, message, _ ->
            if (message.startsWith(EVENT_PREFIX)) events += message
        }
        val config = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .blockedHosts(listOf("пример.рф"))
            .failureSampleRate(0.0)
            .successSampleRate(1.0)
            .build()
        FailoverBeacon.initialize(app, config)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.clear()
        FailoverBeacon.setLogLevel(Log.WARN)
        SdkRegistry.logger.output = previousOutput
    }

    @Test
    fun `reports every host but a blocked internationalized one`() {
        execute("пример.рф")
        execute("a.test")

        assertTrue(events.single().contains(""""host":"a.test""""), events.single())
    }

    @Test
    fun `samples failures and successes at the configured rates`() {
        execute("a.test", status = 500)
        execute("a.test")

        assertTrue(events.single().contains(""""status_code":200"""), events.single())
    }

    @Test
    fun `stores the event for the beacon on disk`() {
        execute("a.test")

        val line = beaconDir().listFiles()!!.single().readText().trimEnd('\n')
        assertEquals(events.single().removePrefix("$EVENT_PREFIX "), line)
    }

    @Test
    fun `keeps at most memoryQueueSize events waiting for the SDK thread`() {
        restart(FailoverBeaconOptions.Builder().memoryQueueSize(1).build())
        val release = CountDownLatch(1)
        assertNotNull(SdkRegistry.instance).storageExecutor.execute { release.await() }

        call("a.test")
        call("a.test")
        release.countDown()
        awaitSdkThread()

        assertEquals(1, events.size)
    }

    @Test
    fun `uploadPeriod sets how long a batch file takes events`() {
        restart(everySecond)

        execute("a.test")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        execute("a.test")

        assertEquals(2, beaconDir().listFiles()!!.size)
    }

    @Test
    fun `diskQueueBytes limits the stored events`() {
        restart(FailoverBeaconOptions.Builder().diskQueueBytes(1).build())

        execute("a.test")
        execute("a.test")

        val batch = beaconDir().listFiles()!!.single()
        assertEquals(listOf(events.last().removePrefix("$EVENT_PREFIX ")), batch.readLines())
    }

    @Test
    fun `uploads stored events to the beacon`() = withBeacon { beacon, url ->
        restart(
            everySecond,
            beacon = url
        )

        execute("a.test")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        val request = assertNotNull(beacon.takeRequest(5, TimeUnit.SECONDS))
        awaitUploadThread()

        val lines = GZIPInputStream(request.body.inputStream()).reader().readLines()
        assertTrue(lines.first().startsWith("""{"sent_at":"""), lines.first())
        assertEquals(listOf(events.single().removePrefix("$EVENT_PREFIX ")), lines.drop(1))
        assertEquals(emptyList(), storedFiles())
    }

    @Test
    fun `uploads events of the previous launch right after initialize`() =
        withBeacon { beacon, url ->
            val defaults = FailoverBeaconOptions.Builder().build()
            restart(defaults, beacon = url)
            execute("a.test")
            FailoverBeacon.stop()
            val batch = storedFiles().single()
            batch.renameTo(File(batch.parentFile, "${System.currentTimeMillis() - 120_000}"))

            restart(defaults, beacon = url)
            val request = assertNotNull(beacon.takeRequest(5, TimeUnit.SECONDS))
            awaitUploadThread()

            val lines = GZIPInputStream(request.body.inputStream()).reader().readLines()
            assertEquals(listOf(events.single().removePrefix("$EVENT_PREFIX ")), lines.drop(1))
            assertEquals(emptyList(), storedFiles())
        }

    @Test
    fun `uploads events stored while consent was pending once it is granted`() =
        withBeacon { beacon, url ->
            restart(everySecond, beacon = url, consent = Consent.PENDING)
            execute("a.test")
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))

            FailoverBeacon.setConsent(Consent.GRANTED)

            val request = assertNotNull(beacon.takeRequest(5, TimeUnit.SECONDS))
            val lines = GZIPInputStream(request.body.inputStream()).reader().readLines()
            assertEquals(listOf(events.single().removePrefix("$EVENT_PREFIX ")), lines.drop(1))
        }

    @Test
    fun `never stores, logs or uploads the path, query or headers of a request`() =
        withBeacon { beacon, url ->
            val logs = CopyOnWriteArrayList<String>()
            restart(everySecond, url)
            FailoverBeacon.setLogger { _, message, throwable -> logs += "$message $throwable" }
            server.enqueue(MockResponse().setBody("ok"))
            val request = Request.Builder()
                .url("http://a.test:${server.port}/$SECRET?token=$SECRET")
                .header("Authorization", SECRET)
                .build()

            client.newCall(request).execute().use { it.body.string() }
            awaitSdkThread()
            val stored = storedFiles().joinToString("\n") { it.readText() }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            val upload = assertNotNull(beacon.takeRequest(5, TimeUnit.SECONDS))
            awaitUploadThread()
            val uploaded = GZIPInputStream(upload.body.inputStream()).reader().readText()

            assertTrue(stored.contains(""""host":"a.test""""), stored)
            (listOf(stored, uploaded) + logs).forEach { assertFalse(it.contains(SECRET), it) }
        }

    @Test
    fun `stops reporting once consent is withdrawn`() {
        execute("a.test")
        FailoverBeacon.setConsent(Consent.NOT_GRANTED)
        execute("a.test")

        assertEquals(1, events.size)
    }

    private fun execute(host: String, status: Int = 200) {
        call(host, status)
        awaitSdkThread()
    }

    private fun call(host: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody("ok"))
        val request = Request.Builder().url("http://$host:${server.port}/").build()
        client.newCall(request).execute().use { it.body.string() }
    }

    private fun restart(
        options: FailoverBeaconOptions,
        beacon: String = "https://beacon.example",
        consent: Consent = Consent.GRANTED
    ) {
        FailoverBeacon.stop()
        val config = FailoverBeaconConfig.Builder()
            .beacons(listOf(beacon))
            .successSampleRate(1.0)
            .options(options)
            .build()
        FailoverBeacon.initialize(app, config, consent)
    }

    private fun withBeacon(block: (beacon: MockWebServer, url: String) -> Unit) {
        val beacon = MockWebServer()
        beacon.useHttps(beaconCertificates.sslSocketFactory(), false)
        beacon.enqueue(MockResponse().setResponseCode(202))
        beacon.start(LOOPBACK, 0)
        val defaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory())
        try {
            block(beacon, "https://localhost:${beacon.port}/events")
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultSocketFactory)
            beacon.shutdown()
        }
    }

    private fun beaconDir() = File(app.cacheDir, "failoverbeacon/$EXAMPLE_BEACON_DIR")

    private fun storedFiles() =
        File(app.cacheDir, "failoverbeacon").walk().filter { it.isFile }.toList()

    private fun awaitSdkThread() {
        val executor = assertNotNull(SdkRegistry.instance).storageExecutor
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun awaitUploadThread() {
        val executor = assertNotNull(SdkRegistry.instance).uploadExecutor
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private companion object {
        const val EVENT_PREFIX = "Telemetry event"
        const val EXAMPLE_BEACON_DIR = "2f7cbe9a31e83f24"
        const val SECRET = "s3cr3t"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        val beaconCertificate: HeldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val beaconCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(beaconCertificate)
            .build()
        val clientCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(beaconCertificate.certificate)
            .build()
    }
}
