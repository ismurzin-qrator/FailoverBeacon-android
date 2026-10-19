package net.qrator.failoverbeacon.telemetry.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorTypeTest {
    @Test
    fun `every error type has the phase NEL groups it under`() {
        ErrorType.entries.forEach { type ->
            val expected = when (type.code.substringBefore('.')) {
                "dns" -> Phase.DNS
                "tcp", "tls" -> Phase.CONNECTION
                else -> Phase.APPLICATION
            }
            assertEquals(expected, type.phase, type.code)
        }
    }
}
