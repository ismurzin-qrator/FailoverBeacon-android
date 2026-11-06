package net.qrator.failoverbeacon.okhttp.internal

import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.okhttp.fakes.RecordingEventListener
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request

class FailoverBeaconEventListenerFactoryTest {
    private val call = OkHttpClient().newCall(Request.Builder().url("http://example.test/").build())

    @AfterTest
    fun tearDown() {
        CallRecordingEventListener.incompatible = false
        SdkRegistry.clear()
    }

    @Test
    fun `returns the app listener without the SDK, a subscriber or a compatible OkHttp`() {
        val appListener = RecordingEventListener()
        val factory = FailoverBeaconEventListenerFactory { appListener }
        val withoutSdk = factory.create(call)
        installSdk()
        val withoutSubscriber = factory.create(call)
        SdkRegistry.clear()
        installSdk({})
        CallRecordingEventListener.incompatible = true

        val incompatible = factory.create(call)

        assertSame(appListener, withoutSdk)
        assertSame(appListener, withoutSubscriber)
        assertSame(appListener, incompatible)
    }

    @Test
    fun `records the call while the SDK is running, with or without an app listener`() {
        val exchanges = CopyOnWriteArrayList<Exchange>()
        installSdk({ exchanges += it })
        val appListener = RecordingEventListener()

        for (app in listOf(appListener, EventListener.NONE)) {
            val listener = FailoverBeaconEventListenerFactory { app }.create(call)
            listener.callStart(call)
            listener.dnsStart(call, "example.test")
            listener.callFailed(call, UnknownHostException("example.test"))
        }

        assertEquals(2, exchanges.size)
        assertEquals(listOf("callStart", "dnsStart", "callFailed"), appListener.log)
    }

    @Test
    fun `app listener exceptions propagate`() {
        installSdk({})
        val failure = IllegalStateException("app bug")
        val factory =
            FailoverBeaconEventListenerFactory { RecordingEventListener(failure = failure) }

        val thrown = assertFailsWith<IllegalStateException> { factory.create(call).callStart(call) }

        assertSame(failure, thrown)
    }
}
