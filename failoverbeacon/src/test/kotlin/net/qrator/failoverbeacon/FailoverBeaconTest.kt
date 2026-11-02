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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
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
    fun `stop stops the SDK thread and the watchers until the next initialize`() {
        FailoverBeacon.initialize(app, config)
        val foreground = SdkRegistry.instance?.foregroundSource
        val executor = threads().storage
        val uploadExecutor = threads().upload

        FailoverBeacon.stop()
        Robolectric.buildActivity(Activity::class.java).setup()
        val stopped = SdkRegistry.instance
        val callbacksAfterStop = networkCallbacks()
        FailoverBeacon.initialize(app, config)

        assertNull(stopped)
        assertEquals(true, executor.isShutdown)
        assertEquals(true, uploadExecutor.isShutdown)
        assertEquals(emptySet(), callbacksAfterStop)
        assertEquals(false, foreground?.isForeground())
        assertNotNull(SdkRegistry.instance)
        assertEquals(1, networkCallbacks().size)
    }

    @Test
    fun `stop finishes when a feature fails to close`() {
        FailoverBeacon.initialize(app, config)
        val instance = assertNotNull(SdkRegistry.instance)
        val executor = threads().storage
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
    fun `a network change reaches the features`() {
        val active = activeNetwork()
        FailoverBeacon.initialize(app, config)
        val changes = mutableListOf<DefaultNetwork?>()
        assertNotNull(SdkRegistry.instance).addFeature(
            object : SdkFeature {
                override fun onNetworkChanged(network: DefaultNetwork?) {
                    changes += network
                }
            }
        )

        networkCallbacks().single().onLost(active)

        assertEquals(listOf<DefaultNetwork?>(null), changes)
    }

    @Test
    fun `failover runs without hosts and stops with the SDK`() {
        FailoverBeacon.initialize(app, config)
        val instance = assertNotNull(SdkRegistry.instance)
        val dns = instance.dns

        FailoverBeacon.stop()

        assertNotNull(dns)
        assertTrue(instance.threads.failoverResolve.isShutdown)
        assertTrue(instance.threads.failoverProbe.isShutdown)
    }

    @Test
    fun `failover gives the transport the addresses of its hosts from the configured DNS`() {
        val failoverConfig = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(listOf(FailoverHost("api.example")))
            .dnsResolver { listOf(InetAddress.getByName("192.0.2.1")) }
            .build()
        activeNetwork()
        FailoverBeacon.initialize(app, failoverConfig)
        val dns = assertNotNull(SdkRegistry.instance?.dns)

        val configured = dns.lookup("api.example")
        val outside = dns.lookup("other.example")

        assertEquals(listOf<String?>("192.0.2.1"), configured?.map { it.hostAddress })
        assertNull(outside)
    }

    @Test
    fun `before a network is known failover leaves its hosts to the client's DNS`() {
        val failoverConfig = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(listOf(FailoverHost("api.example")))
            .dnsResolver { listOf(InetAddress.getByName("192.0.2.1")) }
            .build()

        FailoverBeacon.initialize(app, failoverConfig)

        assertNull(assertNotNull(SdkRegistry.instance?.dns).lookup("api.example"))
    }

    @Test
    fun `failover resolves its hosts on a new network and not on a network it has seen`() {
        val resolutions = CopyOnWriteArrayList<String>()
        val failoverConfig = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(listOf(FailoverHost("api.example", probeConnections = false)))
            .dnsResolver {
                resolutions += it
                listOf(InetAddress.getByName("192.0.2.1"))
            }
            .build()
        activeNetwork()
        FailoverBeacon.initialize(app, failoverConfig)
        val callback = networkCallbacks().single()

        awaitResolutions()
        connect(callback, ShadowNetwork.newInstance(301), NetworkCapabilities.TRANSPORT_CELLULAR)
        awaitResolutions()
        connect(callback, ShadowNetwork.newInstance(302), NetworkCapabilities.TRANSPORT_WIFI)
        awaitResolutions()

        assertEquals(listOf("api.example", "api.example"), resolutions)
    }

    @Test
    fun `with preresolve off a new network is resolved by the first lookup`() {
        val resolutions = CopyOnWriteArrayList<String>()
        val failoverConfig = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(listOf(FailoverHost("api.example", probeConnections = false)))
            .dnsResolver {
                resolutions += it
                listOf(InetAddress.getByName("192.0.2.1"))
            }
            .options(FailoverBeaconOptions.Builder().preresolveOnNetworkChange(false).build())
            .build()
        activeNetwork()
        FailoverBeacon.initialize(app, failoverConfig)
        awaitResolutions()
        val resolvedOnStart = resolutions.size

        connect(
            networkCallbacks().single(),
            ShadowNetwork.newInstance(301),
            NetworkCapabilities.TRANSPORT_CELLULAR
        )
        awaitResolutions()
        val resolvedOnChange = resolutions.size
        val addresses = assertNotNull(SdkRegistry.instance?.dns).lookup("api.example")

        assertEquals(1, resolvedOnStart)
        assertEquals(1, resolvedOnChange)
        assertEquals(2, resolutions.size)
        assertEquals(listOf<String?>("192.0.2.1"), addresses?.map { it.hostAddress })
    }

    @Test
    fun `with probes off a new network is not probed`() {
        val failoverConfig = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example"))
            .failoverHosts(
                listOf(
                    FailoverHost(
                        "api.example",
                        additionalAddresses = listOf("127.0.0.1", "127.0.0.2")
                    )
                )
            )
            .dnsResolver { emptyList() }
            .options(FailoverBeaconOptions.Builder().probeOnNetworkChange(false).build())
            .build()
        activeNetwork()
        FailoverBeacon.initialize(app, failoverConfig)
        val probesOnStart = threads().failoverProbe.taskCount

        connect(
            networkCallbacks().single(),
            ShadowNetwork.newInstance(301),
            NetworkCapabilities.TRANSPORT_CELLULAR
        )

        assertEquals(2, probesOnStart)
        assertEquals(2, threads().failoverProbe.taskCount)
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
        val executor = threads().storage
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

    private fun storeEvent(): File =
        File(app.cacheDir, "failoverbeacon/2f7cbe9a31e83f24/1761040980000").apply {
            parentFile?.mkdirs()
            writeText("{}\n")
        }

    private fun awaitSdkThread() {
        threads().storage.submit {}.get(5, TimeUnit.SECONDS)
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

    private fun connect(callback: NetworkCallback, network: Network, transport: Int) {
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, capabilities(transport))
        callback.onLinkPropertiesChanged(network, LinkProperties())
        callback.onBlockedStatusChanged(network, false)
    }

    private fun capabilities(transport: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(transport) }

    private fun awaitResolutions() {
        val pool = threads().failoverResolve
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (pool.completedTaskCount < pool.taskCount) {
            check(System.nanoTime() < deadline) { "Resolutions did not finish" }
            Thread.sleep(5)
        }
    }
}
