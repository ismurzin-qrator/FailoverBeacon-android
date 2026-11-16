package net.qrator.failoverbeacon.telemetry.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.telemetry.serialization.TelemetryEventSerializer
import net.qrator.failoverbeacon.telemetry.serialization.UploadMetadataSerializer
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchemaSampleTest {
    @Test
    fun `the event serializer writes every valid sample`() {
        val serializer = TelemetryEventSerializer()
        assertWritesSamples("event") { sample ->
            serializer.toJson(SampleReader.event(sample), sample.getDouble("sampling_fraction"))
        }
    }

    @Test
    fun `the metadata serializer writes every valid sample`() {
        val serializer = UploadMetadataSerializer()
        assertWritesSamples("upload-metadata") { serializer.toJson(SampleReader.metadata(it)) }
    }

    private fun assertWritesSamples(schema: String, write: (JSONObject) -> String) {
        val samples = SchemaCopy.validSamples(schema)
        assertTrue(samples.isNotEmpty())
        for (file in samples) {
            val sample = JSONObject(file.readText())
            assertEquals(jsonValue(sample), jsonValue(JSONObject(write(sample))), file.name)
        }
    }
}
