package net.qrator.failoverbeacon.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class DroppedEventsTest {
    private val dropped = DroppedEvents(listOf(A, B))

    @Test
    fun `counts each beacon and reason, reports only what was dropped`() {
        dropped.add(A, DropReason.EXPIRED, 3)
        dropped.add(A, DropReason.EXPIRED, 2)
        dropped.addToAll(DropReason.MEMORY_OVERFLOW, 1)
        dropped.add("https://unknown.example", DropReason.SEND_ERROR, 7)

        assertEquals(
            mapOf(DropReason.MEMORY_OVERFLOW to 1L, DropReason.EXPIRED to 5L),
            dropped.snapshot(A)
        )
        assertEquals(mapOf(DropReason.MEMORY_OVERFLOW to 1L), dropped.snapshot(B))
    }

    @Test
    fun `subtracting a report keeps what was dropped after it`() {
        dropped.add(A, DropReason.DISK_OVERFLOW, 4)
        val reported = dropped.snapshot(A)
        dropped.add(A, DropReason.DISK_OVERFLOW, 1)
        dropped.add(A, DropReason.SEND_ERROR, 2)

        dropped.subtract(A, reported)

        assertEquals(
            mapOf(DropReason.DISK_OVERFLOW to 1L, DropReason.SEND_ERROR to 2L),
            dropped.snapshot(A)
        )
    }

    @Test
    fun `a report sent after clear does not go below zero`() {
        dropped.add(A, DropReason.EXPIRED, 4)
        val reported = dropped.snapshot(A)

        dropped.clear()
        dropped.add(A, DropReason.EXPIRED, 1)
        dropped.subtract(A, reported)
        dropped.add(A, DropReason.EXPIRED, 2)

        assertEquals(mapOf(DropReason.EXPIRED to 2L), dropped.snapshot(A))
    }

    private companion object {
        const val A = "https://a.example"
        const val B = "https://b.example"
    }
}
