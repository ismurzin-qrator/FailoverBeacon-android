package net.qrator.failoverbeacon.telemetry.mapper

import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.telemetry.model.EventTimings

internal class TimingsMapper {
    fun toTimings(timings: ExchangeTimings): EventTimings = EventTimings(
        dnsMs = timings.dnsNanos?.let(::toMillis),
        connectMs = timings.connectNanos?.let(::toMillis),
        tlsMs = timings.tlsNanos?.let(::toMillis),
        sendMs = timings.sendNanos?.let(::toMillis),
        waitMs = timings.waitNanos?.let(::toMillis),
        receiveMs = timings.receiveNanos?.let(::toMillis)
    )

    /** Whole milliseconds, rounded down. */
    fun toMillis(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos)
}
