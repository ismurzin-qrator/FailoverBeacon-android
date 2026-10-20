package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeStep

class TelemetryStepMapperTest {
    @Test
    fun `every step maps to the step of the same name`() {
        val mapper = TelemetryStepMapper()

        ExchangeStep.entries.forEach { assertEquals(it.name, mapper.toStep(it).name) }
    }
}
