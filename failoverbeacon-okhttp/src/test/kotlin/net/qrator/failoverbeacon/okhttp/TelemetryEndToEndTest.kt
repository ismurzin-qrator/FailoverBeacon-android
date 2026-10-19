package net.qrator.failoverbeacon.okhttp

import android.util.Log
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
            .successSampleRate(1.0)
            .build()
        FailoverBeacon.initialize(RuntimeEnvironment.getApplication(), config)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.clear()
        FailoverBeacon.setLogLevel(Log.WARN)
        FailoverBeacon.setLogger(null)
    }

    @Test
    fun `reports a call to a host that is not blocked`() {
        execute("a.test")

        assertTrue(events.single().contains("host=a.test"), events.single())
    }

    @Test
    fun `does not report a blocked internationalized host`() {
        execute("пример.рф")

        assertEquals(emptyList(), events)
    }

    @Test
    fun `does not report after consent is withdrawn`() {
        FailoverBeacon.setConsent(Consent.NOT_GRANTED)

        execute("a.test")

        assertEquals(emptyList(), events)
    }

    private fun execute(host: String) {
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url("http://$host:${server.port}/").build()
        client.newCall(request).execute().use { it.body.string() }
    }

    private companion object {
        const val EVENT_PREFIX = "Telemetry event"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
