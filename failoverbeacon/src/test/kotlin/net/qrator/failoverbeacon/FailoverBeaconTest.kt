package net.qrator.failoverbeacon

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.internal.LogcatLogger
import net.qrator.failoverbeacon.internal.SdkStartup
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock

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
        SdkRegistry.isFailoverEnabled = true
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
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        FailoverBeacon.initialize(app, config)
        val clock = assertNotNull(SdkRegistry.instance).epochClock
        val start = clock.nowMillis()

        ShadowSystemClock.advanceBy(Duration.ofSeconds(65))

        assertTrue(abs(start - System.currentTimeMillis()) < 1_000)
        assertEquals(start + 65_000, clock.nowMillis())
    }

    @Test
    fun `consent is GRANTED by default and maps every value`() {
        FailoverBeacon.initialize(app, config)
        val byDefault = SdkRegistry.instance?.consent
        FailoverBeacon.stop()
        FailoverBeacon.initialize(app, config, Consent.PENDING)
        val pending = SdkRegistry.instance?.consent
        FailoverBeacon.setConsent(Consent.NOT_GRANTED)
        val notGranted = SdkRegistry.instance?.consent
        FailoverBeacon.setConsent(Consent.GRANTED)

        assertEquals(TrackingConsent.GRANTED, byDefault)
        assertEquals(TrackingConsent.PENDING, pending)
        assertEquals(TrackingConsent.NOT_GRANTED, notGranted)
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
        assertEquals(listOf(Log.WARN to "[SDK] Already initialized. Ignored"), logs)
    }

    @Test
    fun `a start that missed the running SDK does not replace it`() {
        FailoverBeacon.initialize(app, config)
        val running = SdkRegistry.instance

        val started = SdkStartup.start(app, TrackingConsent.GRANTED, config)

        assertFalse(started)
        assertSame(running, SdkRegistry.instance)
    }

    @Test
    fun `stays inactive in a secondary process`() {
        ShadowApplication.setProcessName("${app.packageName}:sync")

        FailoverBeacon.initialize(app, config)

        assertNull(SdkRegistry.instance)
        assertEquals(listOf(Log.WARN to "[SDK] Not in the main process. SDK is inactive"), logs)
    }

    @Test
    fun `setConsent, clearAllData and stop warn while the SDK is not running`() {
        FailoverBeacon.setConsent(Consent.GRANTED)
        FailoverBeacon.clearAllData()
        FailoverBeacon.stop()

        assertEquals(
            listOf("setConsent", "clearAllData", "stop").map {
                Log.WARN to "[SDK] Not running. $it ignored"
            },
            logs
        )
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
    fun `stop stops the SDK threads and the watchers until the next initialize`() {
        FailoverBeacon.initialize(app, config)
        val foreground = SdkRegistry.instance?.foregroundSource
        val threads = threads()

        FailoverBeacon.stop()
        Robolectric.buildActivity(Activity::class.java).setup()
        val stopped = SdkRegistry.instance
        val callbacksAfterStop = networkCallbacks()
        FailoverBeacon.initialize(app, config)

        assertNull(stopped)
        assertTrue(threads.serial.isShutdown)
        assertTrue(threads.scheduler.isShutdown)
        assertTrue(threads.dns.isShutdown)
        assertTrue(threads.probes.isShutdown)
        assertEquals(emptySet(), callbacksAfterStop)
        assertEquals(false, foreground?.isForeground())
        assertNotNull(SdkRegistry.instance)
        assertEquals(1, networkCallbacks().size)
    }

    @Test
    fun `stop finishes when a feature fails to close`() {
        FailoverBeacon.initialize(app, config)
        val instance = assertNotNull(SdkRegistry.instance)
        val executor = threads().serial
        instance.addFeature(
            object : SdkFeature {
                override fun close(): Unit = throw IllegalStateException("broken feature")
            }
        )

        FailoverBeacon.stop()

        assertNull(SdkRegistry.instance)
        assertTrue(executor.isShutdown)
        assertEquals(emptySet(), networkCallbacks())
    }

    @Test
    fun `a restart serves the saved DNS answer until clearAllData deletes it`() {
        val saved = File(app.cacheDir, "failoverbeacon-failover.json")
        val working = failoverConfig(FailoverHost(API)) { listOf(ADDRESS) }
        val failing = failoverConfig(FailoverHost(API)) { throw UnknownHostException(it) }
        activeNetwork()
        FailoverBeacon.initialize(app, working)
        awaitSdkThread()
        awaitResolutions()
        awaitUntil("the state was not saved", timeoutSeconds = 30) { saved.exists() }
        FailoverBeacon.stop()

        FailoverBeacon.initialize(app, failing)
        awaitSdkThread()
        val dns = assertNotNull(SdkRegistry.instance?.dns)
        val restored = dns.lookup(API)
        awaitResolutions()
        val afterFailure = dns.lookup(API)

        FailoverBeacon.clearAllData()
        awaitSdkThread()
        val afterClear = dns.lookup(API)

        assertEquals(listOf<String?>(ADDRESS_LITERAL), restored?.map { it.hostAddress })
        assertEquals(listOf<String?>(ADDRESS_LITERAL), afterFailure?.map { it.hostAddress })
        assertNull(afterClear)
        assertFalse(saved.exists())
    }

    @Test
    fun `clearAllData forgets the addresses and resolves again only on the next lookup`() {
        val resolutions = CopyOnWriteArrayList<String>()
        activeNetwork()
        FailoverBeacon.initialize(app, failoverConfig(UNPROBED, resolver = recording(resolutions)))
        awaitSdkThread()
        awaitResolutions()
        val dns = assertNotNull(SdkRegistry.instance?.dns)

        FailoverBeacon.clearAllData()
        awaitSdkThread()
        val resolutionsAfterClear = resolutions.size
        val afterLookup = dns.lookup(API)

        assertEquals(1, resolutionsAfterClear)
        assertTrue(resolutions.size > resolutionsAfterClear)
        assertEquals(listOf<String?>(ADDRESS_LITERAL), afterLookup?.map { it.hostAddress })
    }

    @Test
    fun `failover turned off before initialize leaves hosts to the client's DNS until it is on`() {
        val resolutions = CopyOnWriteArrayList<String>()
        activeNetwork()
        FailoverBeacon.setFailoverEnabled(false)
        FailoverBeacon.initialize(app, failoverConfig(UNPROBED, resolver = recording(resolutions)))
        awaitSdkThread()
        val dns = assertNotNull(SdkRegistry.instance?.dns)
        val whileOff = dns.lookup(API)
        val resolutionsWhileOff = resolutions.size

        FailoverBeacon.setFailoverEnabled(true)
        awaitSdkThread()

        assertNull(whileOff)
        assertEquals(0, resolutionsWhileOff)
        assertEquals(listOf<String?>(ADDRESS_LITERAL), dns.lookup(API)?.map { it.hostAddress })
    }

    @Test
    fun `a hung resolver delays the first lookup up to the limit and later ones not at all`() {
        val release = CountDownLatch(1)
        val host =
            FailoverHost(API, additionalAddresses = listOf(ADDRESS_LITERAL), tcpProbe = false)
        activeNetwork()
        FailoverBeacon.initialize(
            app,
            failoverConfig(host) {
                release.await(30, TimeUnit.SECONDS)
                emptyList()
            }
        )
        awaitSdkThread()
        val dns = assertNotNull(SdkRegistry.instance?.dns)

        val start = Instant.now()
        val first = dns.lookup(API)
        val afterFirst = Instant.now()
        val second = dns.lookup(API)
        val afterSecond = Instant.now()
        release.countDown()

        assertEquals(listOf<String?>(ADDRESS_LITERAL), first?.map { it.hostAddress })
        assertEquals(first, second)
        assertTrue(Duration.between(start, afterFirst) >= WAIT_LIMIT)
        assertTrue(Duration.between(start, afterFirst) < Duration.ofSeconds(20))
        assertTrue(Duration.between(afterFirst, afterSecond) < WAIT_LIMIT)
    }

    @Test
    fun `stop releases a lookup waiting for a hung resolver`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        activeNetwork()
        FailoverBeacon.initialize(
            app,
            failoverConfig(UNPROBED) {
                entered.countDown()
                while (release.count > 0) {
                    runCatching { release.await() }
                }
                emptyList()
            }
        )
        awaitSdkThread()
        val dns = assertNotNull(SdkRegistry.instance?.dns)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val lookup = thread { dns.lookup(API) }
        try {
            awaitUntil("the lookup did not wait") { lookup.state == Thread.State.TIMED_WAITING }

            FailoverBeacon.stop()

            // Well below the 2 s limit of the wait, which would end it without stop.
            lookup.join(TimeUnit.SECONDS.toMillis(1))
            assertFalse(lookup.isAlive, "the lookup waited after stop")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a blocked network is left to the client's DNS until it is unblocked`() {
        val resolutions = CopyOnWriteArrayList<String>()
        val resolver = HostResolver {
            resolutions += it
            emptyList()
        }
        FailoverBeacon.initialize(app, failoverConfig(PROBED, resolver = resolver))
        awaitSdkThread()
        val callback = networkCallbacks().single()
        val network = ShadowNetwork.newInstance(301)
        val dns = assertNotNull(SdkRegistry.instance?.dns)

        connect(callback, network, NetworkCapabilities.TRANSPORT_WIFI, blocked = true)
        val whileBlocked = dns.lookup(API)
        val probesWhileBlocked = threads().probes.taskCount
        val resolutionsWhileBlocked = resolutions.size
        callback.onBlockedStatusChanged(network, false)
        awaitSdkThread()
        awaitResolutions()

        assertNull(whileBlocked)
        assertEquals(0, probesWhileBlocked)
        assertEquals(0, resolutionsWhileBlocked)
        assertEquals(2, threads().probes.taskCount)
        assertEquals(listOf(API), resolutions)
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
        assertTrue(
            logs.contains(Log.ERROR to "[SDK] Cannot start the SDK. SDK is inactive"),
            "$logs"
        )
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
        val executor = threads().serial
        val release = CountDownLatch(1)
        executor.execute { release.await() }

        FailoverBeacon.setConsent(Consent.NOT_GRANTED)
        FailoverBeacon.stop()
        release.countDown()

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(event.exists())
    }

    @Test
    fun `a consent set while initialize runs is the one published`() {
        FailoverBeacon.initialize(app, config)
        val instance = assertNotNull(SdkRegistry.instance)
        val published = CopyOnWriteArrayList<TrackingConsent>()
        instance.addFeature(
            object : SdkFeature {
                override fun onConsentChanged(consent: TrackingConsent) {
                    published += consent
                }
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

    private fun failoverConfig(
        host: FailoverHost,
        options: FailoverBeaconOptions = FailoverBeaconOptions.Builder().build(),
        resolver: HostResolver
    ) = FailoverBeaconConfig.Builder()
        .beacons(listOf("https://beacon.example"))
        .failoverHosts(listOf(host))
        .dnsResolver(resolver)
        .options(options)
        .build()

    private fun recording(resolutions: MutableList<String>) = HostResolver {
        resolutions += it
        listOf(ADDRESS)
    }

    private fun storeEvent(): File =
        File(app.cacheDir, "failoverbeacon/2f7cbe9a31e83f24/1761040980000").apply {
            parentFile?.mkdirs()
            writeText("{}\n")
        }

    private fun awaitSdkThread() {
        threads().serial.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun threads() = assertNotNull(SdkRegistry.instance).threads

    private fun networkCallbacks() =
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks.toSet()

    private fun activeNetwork(): Network {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val active = assertNotNull(manager.activeNetwork)
        shadowOf(manager).apply {
            setNetworkCapabilities(active, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            setLinkProperties(active, LinkProperties())
        }
        return active
    }

    private fun connect(
        callback: NetworkCallback,
        network: Network,
        transport: Int,
        blocked: Boolean = false
    ) {
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, capabilities(transport))
        callback.onLinkPropertiesChanged(network, LinkProperties())
        callback.onBlockedStatusChanged(network, blocked)
        awaitSdkThread()
    }

    private fun capabilities(transport: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(transport) }

    private fun awaitResolutions() {
        val pool = threads().dns
        awaitUntil("resolutions did not finish") { pool.completedTaskCount == pool.taskCount }
    }

    private fun awaitUntil(failure: String, timeoutSeconds: Long = 5, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { failure }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val API = "api.example"
        const val ADDRESS_LITERAL = "192.0.2.1"
        val ADDRESS: InetAddress = InetAddress.getByName(ADDRESS_LITERAL)
        val UNPROBED = FailoverHost(API, tcpProbe = false)

        // Two IPv4 addresses, so the host is probed.
        val PROBED = FailoverHost(API, additionalAddresses = listOf("127.0.0.1", "127.0.0.2"))
        val WAIT_LIMIT: Duration = Duration.ofSeconds(2)
        const val POLL_MILLIS = 10L
    }
}
