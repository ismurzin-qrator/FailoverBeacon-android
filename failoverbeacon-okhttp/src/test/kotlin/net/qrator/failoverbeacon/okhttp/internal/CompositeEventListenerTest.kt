package net.qrator.failoverbeacon.okhttp.internal

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import net.qrator.failoverbeacon.okhttp.fakes.RecordingEventListener
import net.qrator.failoverbeacon.okhttp.fakes.eventListenerEvents
import net.qrator.failoverbeacon.okhttp.fakes.notOverriddenEvents
import net.qrator.failoverbeacon.okhttp.fakes.sendEvent

class CompositeEventListenerTest {
    private val log = CopyOnWriteArrayList<String>()

    @Test
    fun `overrides every event`() {
        assertEquals(emptyList(), CompositeEventListener::class.java.notOverriddenEvents())
    }

    @Test
    fun `sends every event to the first listener and then to the second`() {
        val listener = CompositeEventListener(
            RecordingEventListener(log, "first:"),
            RecordingEventListener(log, "second:")
        )

        eventListenerEvents.forEach { sendEvent(listener, it) }

        val expected = eventListenerEvents.flatMap {
            listOf("first:${it.name}", "second:${it.name}")
        }
        assertEquals(expected, log)
    }

    @Test
    fun `exceptions of the second listener propagate after the first listener gets the event`() {
        val failure = IllegalStateException("app bug")
        val listener = CompositeEventListener(
            RecordingEventListener(log, "first:"),
            RecordingEventListener(log, "second:", failure)
        )

        val thrown = assertFailsWith<IllegalStateException> {
            sendEvent(listener, eventListenerEvents.first())
        }

        assertSame(failure, thrown)
        val event = eventListenerEvents.first().name
        assertEquals(listOf("first:$event", "second:$event"), log)
    }
}
