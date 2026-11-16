package net.qrator.failoverbeacon.telemetry.model

import net.qrator.failoverbeacon.telemetry.DropReason

internal data class UploadMetadata(
    val sentAtMillis: Long,
    val sdkVersion: String,
    val schemaVersion: String,
    val appPackage: String,
    val appVersion: String?,
    val osApiLevel: Int,
    val dropped: Map<DropReason, Long>
)
