package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.telemetry.model.Phase

class PhaseMapperTest {
    private val mapper = PhaseMapper()

    @Test
    fun `the phase follows the step`() {
        val expected = mapOf(
            ExchangeStep.DNS to Phase.DNS,
            ExchangeStep.TCP to Phase.CONNECTION,
            ExchangeStep.TLS to Phase.CONNECTION,
            ExchangeStep.REQUEST to Phase.APPLICATION,
            ExchangeStep.RESPONSE to Phase.APPLICATION
        )

        assertEquals(expected, expected.mapValues { (step, _) -> mapper.toPhase(step) })
        assertEquals(Phase.APPLICATION, mapper.toPhase(null))
    }
}
