package net.qrator.failoverbeacon

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.DataOwner
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
    fun `event time starts at the device clock and advances with the elapsed realtime`() {
        SystemClock.sleep(3_600_000)
        FailoverBeacon.initialize(app, config)
        val clock = assertNotNull(SdkRegistry.instance).epochClock
        val start = clock.nowMillis()

        SystemClock.sleep(65_000)

        assertTrue(abs(start - System.currentTimeMillis()) < 1_000)
        assertEquals(start + 65_000, clock.nowMillis())
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
    fun `stop stops the SDK thread and the watchers until the next initialize`() {
        FailoverBeacon.initialize(app, config)
        val foreground = SdkRegistry.instance?.foregroundSource
        val executor = SdkRegistry.instance?.storageExecutor
        val uploadExecutor = SdkRegistry.instance?.uploadExecutor

        FailoverBeacon.stop()
        Robolectric.buildActivity(Activity::class.java).setup()
        val stopped = SdkRegistry.instance
        val callbacksAfterStop = networkCallbacks()
        FailoverBeacon.initialize(app, config)

        assertNull(stopped)
        assertEquals(true, executor?.isShutdown)
        assertEquals(true, uploadExecutor?.isShutdown)
        assertEquals(emptySet(), callbacksAfterStop)
        assertEquals(false, foreground?.isForeground())
        assertNotNull(SdkRegistry.instance)
        assertEquals(1, networkCallbacks().size)
    }

    @Test
    fun `uploads at start and then every upload period`() {
        val options = FailoverBeaconOptions.Builder().uploadPeriod(5, TimeUnit.MINUTES).build()
        FailoverBeacon.initialize(
            app,
            FailoverBeaconConfig.Builder().beacons(config.beacons).options(options).build()
        )

        val executor = assertNotNull(
            SdkRegistry.instance
        ).uploadExecutor as ScheduledThreadPoolExecutor
        executor.submit {}.get(5, TimeUnit.SECONDS)
        val upload = executor.queue.single() as ScheduledFuture<*>

        assertTrue(upload.getDelay(TimeUnit.MILLISECONDS) in 290_000..300_000)
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
    fun `stays inactive when it cannot start`() {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context? = null

            override fun getSystemService(name: String): Any? = throw IllegalStateException()
        }

        FailoverBeacon.initialize(context, config)

        assertNull(SdkRegistry.instance)
        assertTrue(logs.contains(Log.ERROR to "Cannot start the SDK. SDK is inactive"), "$logs")
    }

    @Test
    fun `a start that fails after install is rolled back`() {
        val application = object : Application() {
            init {
                attachBaseContext(app)
            }

            override fun getApplicationContext(): Context = this

            override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) =
                throw IllegalStateException()
        }
        val callbacks = networkCallbacks()

        FailoverBeacon.initialize(application, config)

        assertNull(SdkRegistry.instance)
        assertEquals(callbacks, networkCallbacks())
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

    @Test
    fun `clearAllData deletes stored events and keeps consent`() {
        FailoverBeacon.initialize(app, config)
        val event = storeEvent()

        FailoverBeacon.clearAllData()
        awaitSdkThread()

        assertFalse(event.exists())
        assertEquals(TrackingConsent.GRANTED, SdkRegistry.instance?.consent)
    }

    @Test
    fun `withdrawn consent deletes stored events, pending and granted keep them`() {
        FailoverBeacon.initialize(app, config)
        val event = storeEvent()

        FailoverBeacon.setConsent(Consent.PENDING)
        FailoverBeacon.setConsent(Consent.GRANTED)
        awaitSdkThread()
        val kept = event.exists()
        FailoverBeacon.setConsent(Consent.NOT_GRANTED)
        awaitSdkThread()

        assertTrue(kept)
        assertFalse(event.exists())
    }

    @Test
    fun `withdrawn consent right before stop still deletes stored events`() {
        FailoverBeacon.initialize(app, config)
        val event = storeEvent()
        val executor = assertNotNull(SdkRegistry.instance).storageExecutor
        val release = CountDownLatch(1)
        executor.execute { release.await() }

        FailoverBeacon.setConsent(Consent.NOT_GRANTED)
        FailoverBeacon.stop()
        release.countDown()

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(event.exists())
    }

    @Test
    fun `publishing consent sends the current value and keeps a change made meanwhile`() {
        FailoverBeacon.initialize(app, config)
        val instance = assertNotNull(SdkRegistry.instance)
        val published = CopyOnWriteArrayList<TrackingConsent>()
        instance.addDataOwner(
            object : DataOwner {
                override fun onConsentChanged(consent: TrackingConsent) {
                    published += consent
                }

                override fun clearData() = Unit
            }
        )

        FailoverBeacon.setConsent(Consent.NOT_GRANTED)
        instance.publishConsent()

        assertEquals(TrackingConsent.NOT_GRANTED, instance.consent)
        assertEquals(listOf(TrackingConsent.NOT_GRANTED, TrackingConsent.NOT_GRANTED), published)
    }

    @Test
    fun `initialize without consent deletes events of an earlier run`() {
        val event = storeEvent()

        FailoverBeacon.initialize(app, config, Consent.NOT_GRANTED)
        awaitSdkThread()

        assertFalse(event.exists())
    }

    @Test
    fun `initialize with consent and stop keep stored events`() {
        val event = storeEvent()

        FailoverBeacon.initialize(app, config, Consent.PENDING)
        awaitSdkThread()
        FailoverBeacon.stop()

        assertTrue(event.exists())
    }

    private fun storeEvent(): File =
        File(app.cacheDir, "failoverbeacon/2f7cbe9a31e83f24/1761040980000").apply {
            parentFile?.mkdirs()
            writeText("{}\n")
        }

    private fun awaitSdkThread() {
        assertNotNull(SdkRegistry.instance).storageExecutor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun networkCallbacks() =
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks.toSet()

    private companion object {
        const val ROUNDS = 20
    }
}
