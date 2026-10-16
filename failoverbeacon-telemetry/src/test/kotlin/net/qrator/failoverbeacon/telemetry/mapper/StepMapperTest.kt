package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeStep

class StepMapperTest {
    @Test
    fun `every step maps to the step of the same name`() {
        val mapper = StepMapper()

        ExchangeStep.entries.forEach { assertEquals(it.name, mapper.toStep(it).name) }
    }
}
