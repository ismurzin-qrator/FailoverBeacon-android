package net.qrator.failoverbeacon.telemetry

import java.util.concurrent.atomic.AtomicLongArray

internal class DroppedEvents(beacons: List<String>) {
    private val counts = beacons.associateWith { AtomicLongArray(DropReason.entries.size) }

    fun add(beacon: String, reason: DropReason, count: Long) {
        counts[beacon]?.addAndGet(reason.ordinal, count)
    }

    fun addToAll(reason: DropReason, count: Long) {
        counts.values.forEach { it.addAndGet(reason.ordinal, count) }
    }

    fun snapshot(beacon: String): Map<DropReason, Long> {
        val beaconCounts = counts[beacon] ?: return emptyMap()
        return DropReason.entries
            .associateWith { beaconCounts.get(it.ordinal) }
            .filterValues { it > 0 }
    }

    fun subtract(beacon: String, sent: Map<DropReason, Long>) {
        val beaconCounts = counts[beacon] ?: return
        sent.forEach { (reason, count) ->
            beaconCounts.updateAndGet(reason.ordinal) { (it - count).coerceAtLeast(0) }
        }
    }

    fun clear() {
        counts.values.forEach { beaconCounts ->
            DropReason.entries.forEach { beaconCounts.set(it.ordinal, 0) }
        }
    }
}
