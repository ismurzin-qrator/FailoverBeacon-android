package net.qrator.failoverbeacon.telemetry.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.telemetry.DropReason
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttemptResult
import net.qrator.failoverbeacon.telemetry.model.TelemetryErrorType
import net.qrator.failoverbeacon.telemetry.model.TelemetryNetworkTransport
import net.qrator.failoverbeacon.telemetry.model.TelemetryPhase
import net.qrator.failoverbeacon.telemetry.model.TelemetryProxyType
import net.qrator.failoverbeacon.telemetry.model.TelemetryStep
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchemaEnumTest {
    private val event = JSONObject(SchemaCopy.text("schemas/v1/event.schema.json"))
    private val metadata = JSONObject(SchemaCopy.text("schemas/v1/upload-metadata.schema.json"))

    @Test
    fun `every enum has exactly the schema's codes`() {
        assertCodes("/\$defs/error_type", TelemetryErrorType.entries.map { it.code })
        assertCodes("/\$defs/step", TelemetryStep.entries.map { it.code })
        assertCodes("/\$defs/proxy", TelemetryProxyType.entries.map { it.code })
        assertCodes("/properties/phase", TelemetryPhase.entries.map { it.code })
        assertCodes(
            "/properties/attempts/items/properties/result",
            TelemetryAttemptResult.entries.map { it.code }
        )
        assertCodes(
            "/properties/network/properties/transport",
            TelemetryNetworkTransport.entries.map { it.code }
        )
    }

    @Test
    fun `drop reasons are the schema's`() {
        assertEquals(
            metadata.at("/properties/dropped/properties").keys().asSequence().toSet(),
            DropReason.entries.map { it.code }.toSet()
        )
    }

    private fun assertCodes(pointer: String, codes: List<String>) {
        val enum = event.at(pointer).getJSONArray("enum")
        assertEquals(List(enum.length()) { enum.getString(it) }.toSet(), codes.toSet(), pointer)
    }

    private fun JSONObject.at(pointer: String): JSONObject =
        pointer.removePrefix("/").split("/").fold(this) { node, key -> node.getJSONObject(key) }
}
