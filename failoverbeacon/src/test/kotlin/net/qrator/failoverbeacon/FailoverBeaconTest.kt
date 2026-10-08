package net.qrator.failoverbeacon

import android.app.Application
import android.content.ContextWrapper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.internal.LogcatLogger
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class FailoverBeaconTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val config = FailoverBeaconConfig.Builder()
        .beacons(listOf("https://beacon.example"))
        .build()
    private val logs = CopyOnWriteArrayList<Pair<Int, String>>()

    init {
        FailoverBeacon.setLogger { level, message, _ -> logs += level to message }
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.clear()
        SdkRegistry.logger.minLevel = Log.WARN
        SdkRegistry.logger.output = LogcatLogger
    }

    @Test
    fun `keeps the application context instead of the given one`() {
        FailoverBeacon.initialize(ContextWrapper(app), config)

        assertSame(app, SdkRegistry.instance?.appContext)
    }

    @Test
    fun `consent is GRANTED by default`() {
        FailoverBeacon.initialize(app, config)

        assertEquals(TrackingConsent.GRANTED, SdkRegistry.instance?.consent)
    }

    @Test
    fun `maps every consent value`() {
        FailoverBeacon.initialize(app, config, Consent.PENDING)
        assertEquals(TrackingConsent.PENDING, SdkRegistry.instance?.consent)

        FailoverBeacon.setConsent(Consent.NOT_GRANTED)
        assertEquals(TrackingConsent.NOT_GRANTED, SdkRegistry.instance?.consent)

        FailoverBeacon.setConsent(Consent.GRANTED)
        assertEquals(TrackingConsent.GRANTED, SdkRegistry.instance?.consent)
    }

    @Test
    fun `repeated initialize is ignored with a warning`() {
        FailoverBeacon.initialize(app, config, Consent.PENDING)
        val first = SdkRegistry.instance

        FailoverBeacon.initialize(app, config, Consent.GRANTED)

        assertSame(first, SdkRegistry.instance)
        assertEquals(TrackingConsent.PENDING, SdkRegistry.instance?.consent)
        assertEquals(listOf(Log.WARN to "Already initialized. Ignored"), logs)
    }

    @Test
    fun `stays inactive in a secondary process`() {
        ShadowApplication.setProcessName("${app.packageName}:sync")

        FailoverBeacon.initialize(app, config)

        assertNull(SdkRegistry.instance)
        assertEquals(listOf(Log.WARN to "Not the main process. SDK is inactive"), logs)
    }

    @Test
    fun `setConsent and clearAllData warn while the SDK is not running`() {
        FailoverBeacon.setConsent(Consent.GRANTED)
        FailoverBeacon.clearAllData()

        assertEquals(listOf(Log.WARN, Log.WARN), logs.map { it.first })
    }

    @Test
    fun `clearAllData after initialize does not warn`() {
        FailoverBeacon.initialize(app, config)

        FailoverBeacon.clearAllData()

        assertTrue(logs.none { it.first == Log.WARN })
    }

    @Test
    fun `concurrent initialize installs one instance`() {
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                FailoverBeacon.initialize(app, config)
            }
        }

        start.countDown()
        pool.shutdown()

        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertNotNull(SdkRegistry.instance)
        assertEquals(threads - 1, logs.count { it.second == "Already initialized. Ignored" })
    }

    @Test
    fun `setLogLevel rejects values outside Log constants`() {
        assertFailsWith<IllegalArgumentException> { FailoverBeacon.setLogLevel(0) }
    }

    @Test
    fun `setLogLevel filters messages`() {
        FailoverBeacon.setLogLevel(Log.ERROR)

        FailoverBeacon.setConsent(Consent.GRANTED)

        assertTrue(logs.isEmpty())
    }

    @Test
    fun `logs to Logcat by default`() {
        SdkRegistry.logger.output = LogcatLogger

        FailoverBeacon.setConsent(Consent.GRANTED)

        assertEquals(Log.WARN, ShadowLog.getLogsForTag("FailoverBeacon").single().type)
    }

    @Test
    fun `setLogger null disables logging`() {
        FailoverBeacon.setLogger(null)

        FailoverBeacon.setConsent(Consent.GRANTED)

        assertTrue(logs.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("FailoverBeacon").isEmpty())
    }

    @Test
    fun `exceptions from the app logger do not escape`() {
        FailoverBeacon.setLogger { _, _, _ -> throw IllegalStateException() }

        FailoverBeacon.setConsent(Consent.GRANTED)
    }
}
