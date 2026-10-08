package net.qrator.failoverbeacon.okhttp.internal

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.okhttp.fakes.RecordingEventListener
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request

class FailoverBeaconEventListenerFactoryTest {
    private val call = OkHttpClient().newCall(Request.Builder().url("http://example.test/").build())

    @AfterTest
    fun tearDown() {
        SdkRegistry.clear()
    }

    @Test
    fun `returns the app listener while the SDK is not running`() {
        val appListener = RecordingEventListener()

        val listener = FailoverBeaconEventListenerFactory { appListener }.create(call)

        assertSame(appListener, listener)
    }

    @Test
    fun `adds the SDK listener while the SDK is running`() {
        installSdk()
        val appListener = RecordingEventListener()

        val listener = FailoverBeaconEventListenerFactory { appListener }.create(call)
        listener.callStart(call)

        assertNotSame(appListener, listener)
        assertEquals(listOf("callStart"), appListener.log)
    }

    @Test
    fun `returns only the SDK listener when the app has none`() {
        installSdk()

        val listener = FailoverBeaconEventListenerFactory { EventListener.NONE }.create(call)

        assertIs<FailureIsolatingEventListener>(listener)
    }

    @Test
    fun `app listener exceptions propagate`() {
        installSdk()
        val failure = IllegalStateException("app bug")
        val factory =
            FailoverBeaconEventListenerFactory { RecordingEventListener(failure = failure) }

        val thrown = assertFailsWith<IllegalStateException> { factory.create(call).callStart(call) }

        assertSame(failure, thrown)
    }
}
