package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ProxyType

class TelemetryProxyTypeMapperTest {
    @Test
    fun `every proxy type maps to the type of the same name`() {
        val mapper = TelemetryProxyTypeMapper()

        ProxyType.entries.forEach { assertEquals(it.name, mapper.toProxyType(it).name) }
    }
}
