package net.qrator.failoverbeacon.okhttp

import android.util.Log
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.Consent
import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.core.SdkRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TelemetryEndToEndTest {
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<String>()
    private val client = OkHttpClient.Builder().dns {
        listOf(LOOPBACK)
    }.build().withFailoverBeacon()
    private val previousOutput = SdkRegistry.logger.output

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
        FailoverBeacon.initialize(RuntimeEnvironment.getApplication(), config)
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

        assertTrue(events.single().contains("host=a.test"), events.single())
    }

    @Test
    fun `samples failures and successes at the configured rates`() {
        execute("a.test", status = 500)
        execute("a.test")

        assertTrue(events.single().contains("statusCode=200"), events.single())
    }

    @Test
    fun `stops reporting once consent is withdrawn`() {
        execute("a.test")
        FailoverBeacon.setConsent(Consent.NOT_GRANTED)
        execute("a.test")

        assertEquals(1, events.size)
    }

    private fun execute(host: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody("ok"))
        val request = Request.Builder().url("http://$host:${server.port}/").build()
        client.newCall(request).execute().use { it.body.string() }
        awaitSdkThread()
    }

    private fun awaitSdkThread() {
        val executor = assertNotNull(SdkRegistry.instance).storageExecutor
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private companion object {
        const val EVENT_PREFIX = "Telemetry event"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
