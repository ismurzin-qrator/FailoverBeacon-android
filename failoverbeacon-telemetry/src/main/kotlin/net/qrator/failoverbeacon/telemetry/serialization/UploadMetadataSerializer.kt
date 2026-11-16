package net.qrator.failoverbeacon.telemetry.serialization

import android.util.JsonWriter
import java.io.StringWriter
import net.qrator.failoverbeacon.telemetry.model.UploadMetadata

internal class UploadMetadataSerializer {
    fun toJson(metadata: UploadMetadata): String {
        val out = StringWriter()
        JsonWriter(out).use { it.writeMetadata(metadata) }
        return out.toString()
    }

    private fun JsonWriter.writeMetadata(metadata: UploadMetadata) {
        beginObject()
        name("sent_at").value(metadata.sentAtMillis)
        name("sdk_version").value(metadata.sdkVersion)
        name("schema_version").value(metadata.schemaVersion)
        name("app_package").value(metadata.appPackage)
        metadata.appVersion?.let { name("app_version").value(it) }
        name("os_api_level").value(metadata.osApiLevel.toLong())
        if (metadata.dropped.isNotEmpty()) {
            name("dropped").beginObject()
            metadata.dropped.forEach { (reason, count) -> name(reason.code).value(count) }
            endObject()
        }
        endObject()
    }
}
