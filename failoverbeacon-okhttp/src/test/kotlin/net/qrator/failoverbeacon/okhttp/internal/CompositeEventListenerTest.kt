package net.qrator.failoverbeacon.okhttp.internal

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.okhttp.fakes.RecordingEventListener
import net.qrator.failoverbeacon.okhttp.fakes.eventListenerEvents
import net.qrator.failoverbeacon.okhttp.fakes.sendEvent

class CompositeEventListenerTest {
    private val log = CopyOnWriteArrayList<String>()

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
}
