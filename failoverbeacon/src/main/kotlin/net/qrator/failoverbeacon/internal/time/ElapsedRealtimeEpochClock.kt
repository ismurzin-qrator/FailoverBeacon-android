package net.qrator.failoverbeacon.internal.time

import android.os.SystemClock
import net.qrator.failoverbeacon.core.time.EpochClock

internal class ElapsedRealtimeEpochClock : EpochClock {
    private val offsetMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    override fun nowMillis(): Long = offsetMillis + SystemClock.elapsedRealtime()
}
