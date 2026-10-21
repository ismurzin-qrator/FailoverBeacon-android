package net.qrator.failoverbeacon

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
        assertSame<Any?>(first?.networkSource, networkCallbacks().single())
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
    fun `setConsent, clearAllData and stop warn while the SDK is not running`() {
        FailoverBeacon.setConsent(Consent.GRANTED)
        FailoverBeacon.clearAllData()
        FailoverBeacon.stop()

        assertEquals(
            listOf("setConsent", "clearAllData", "stop").map {
                Log.WARN to "SDK is not running. $it ignored"
            },
            logs
        )
    }

    @Test
    fun `concurrent initialize installs one instance`() {
        // Rounds make it near certain that some calls race past the first check.
        repeat(ROUNDS) {
            SdkRegistry.clear()
            logs.clear()
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
            assertSame<Any?>(
                SdkRegistry.instance?.networkSource,
                networkCallbacks().single()
            )
            assertEquals(threads - 1, logs.count { it.second == "Already initialized. Ignored" })
        }
    }

    @Test
    fun `tracks whether the app has a started activity`() {
        FailoverBeacon.initialize(app, config)
        val foreground = { SdkRegistry.instance?.foregroundSource?.isForeground() }
        val before = foreground()
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        first.pause().stop()
        val oneStarted = foreground()
        second.pause().stop()

        assertEquals(false, before)
        assertEquals(true, oneStarted)
        assertEquals(false, foreground())
    }

    @Test
    fun `an activity started before initialize does not hide later ones`() {
        val early = Robolectric.buildActivity(Activity::class.java).setup()
        FailoverBeacon.initialize(app, config)
        early.pause().stop()
        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals(true, SdkRegistry.instance?.foregroundSource?.isForeground())
    }

    @Test
    fun `stop stops watching the network and the activities until the next initialize`() {
        FailoverBeacon.initialize(app, config)
        val foreground = SdkRegistry.instance?.foregroundSource

        FailoverBeacon.stop()
        Robolectric.buildActivity(Activity::class.java).setup()
        val stopped = SdkRegistry.instance
        val callbacksAfterStop = networkCallbacks()
        FailoverBeacon.initialize(app, config)

        assertNull(stopped)
        assertEquals(emptySet(), callbacksAfterStop)
        assertEquals(false, foreground?.isForeground())
        assertNotNull(SdkRegistry.instance)
        assertEquals(1, networkCallbacks().size)
    }

    @Test
    fun `without an application the foreground state is unknown`() {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context? = null
        }

        FailoverBeacon.initialize(context, config)

        assertNotNull(SdkRegistry.instance)
        assertNull(SdkRegistry.instance?.foregroundSource?.isForeground())
    }

    @Test
    fun `setLogLevel rejects values outside Log constants`() {
        assertFailsWith<IllegalArgumentException> { FailoverBeacon.setLogLevel(0) }
    }

    @Test
    fun `setLogger null disables logging`() {
        FailoverBeacon.setLogger(null)

        FailoverBeacon.setConsent(Consent.GRANTED)

        assertTrue(logs.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("FailoverBeacon").isEmpty())
    }

    private fun networkCallbacks() =
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks.toSet()

    private companion object {
        const val ROUNDS = 20
    }
}
