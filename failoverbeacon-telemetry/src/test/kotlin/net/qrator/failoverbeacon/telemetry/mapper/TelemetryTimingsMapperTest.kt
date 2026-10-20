package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.telemetry.model.TelemetryTimings

class TelemetryTimingsMapperTest {
    private val mapper = TelemetryTimingsMapper()

    @Test
    fun `maps every phase to milliseconds`() {
        val timings = ExchangeTimings(1 * MS, 20 * MS, 12 * MS, 2 * MS, 30 * MS, null)

        assertEquals(TelemetryTimings(1, 20, 12, 2, 30, null), mapper.toTimings(timings))
    }

    @Test
    fun `milliseconds are whole milliseconds rounded down`() {
        assertEquals(1, mapper.toMillis(2 * MS - 1))
        assertEquals(0, mapper.toMillis(MS - 1))
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
