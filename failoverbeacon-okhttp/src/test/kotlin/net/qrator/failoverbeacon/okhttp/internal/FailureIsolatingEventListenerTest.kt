package net.qrator.failoverbeacon.okhttp.internal

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.okhttp.fakes.RecordingEventListener
import net.qrator.failoverbeacon.okhttp.fakes.eventListenerEvents
import net.qrator.failoverbeacon.okhttp.fakes.notOverriddenEvents
import net.qrator.failoverbeacon.okhttp.fakes.sendEvent

class FailureIsolatingEventListenerTest {
    private val logs = CopyOnWriteArrayList<Pair<Int, Throwable?>>()
    private val previousOutput = SdkRegistry.logger.output

    @BeforeTest
    fun setUp() {
        FailureIsolatingEventListener.failureLogged.set(false)
        SdkRegistry.logger.output = LogOutput { level, _, throwable -> logs += level to throwable }
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = previousOutput
    }

    @Test
    fun `overrides every event`() {
        assertEquals(emptyList(), FailureIsolatingEventListener::class.java.notOverriddenEvents())
    }

    @Test
    fun `sends every event to the delegate`() {
        val delegate = RecordingEventListener()
        val listener = FailureIsolatingEventListener(delegate)

        eventListenerEvents.forEach { sendEvent(listener, it) }

        assertEquals(eventListenerEvents.map { it.name }, delegate.log)
    }

    @Test
    fun `contains exceptions and linkage errors of the delegate`() {
        val failures = listOf(
            IllegalStateException("SDK bug"),
            NoSuchMethodError("missing in this OkHttp version")
        )

        failures.forEach {
            sendEvent(
                FailureIsolatingEventListener(RecordingEventListener(failure = it)),
                eventListenerEvents.first()
            )
        }
    }

    @Test
    fun `other errors of the delegate propagate`() {
        val failure = AssertionError("test")
        val listener = FailureIsolatingEventListener(RecordingEventListener(failure = failure))

        val thrown = assertFailsWith<AssertionError> {
            sendEvent(listener, eventListenerEvents.first())
        }

        assertSame(failure, thrown)
    }

    @Test
    fun `stops sending events of the call after the first failure`() {
        val delegate = RecordingEventListener(failure = IllegalStateException("SDK bug"))
        val listener = FailureIsolatingEventListener(delegate)

        eventListenerEvents.forEach { sendEvent(listener, it) }

        assertEquals(listOf(eventListenerEvents.first().name), delegate.log)
    }

    @Test
    fun `logs only the first failure in the process`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")

        listOf(first, second).forEach {
            sendEvent(
                FailureIsolatingEventListener(RecordingEventListener(failure = it)),
                eventListenerEvents.first()
            )
        }

        assertEquals(listOf<Pair<Int, Throwable?>>(Log.ERROR to first), logs)
    }
}
