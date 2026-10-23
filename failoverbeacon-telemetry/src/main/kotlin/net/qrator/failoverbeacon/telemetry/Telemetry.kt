package net.qrator.failoverbeacon.telemetry

import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.telemetry.storage.EventStorage
import net.qrator.failoverbeacon.telemetry.upload.TelemetryUploader

class Telemetry(
    private val sdk: SdkInstance,
    beacons: List<String>,
    blockedHosts: Set<String>,
    failureSampleRate: Double,
    successSampleRate: Double,
    memoryQueueSize: Int,
    private val uploadPeriodMillis: Long,
    diskQueueBytes: Long
) {
    private val dropped = DroppedEvents(beacons)
    private val storage = EventStorage(
        cacheDir = sdk.appContext::getCacheDir,
        beacons = beacons,
        clock = sdk.epochClock,
        batchWindowMillis = uploadPeriodMillis,
        maxBytes = diskQueueBytes,
        dropped = dropped
    )
    private val collector = TelemetryCollector(
        consentSource = sdk,
        blockedHosts = blockedHosts,
        failureSampleRate = failureSampleRate,
        successSampleRate = successSampleRate,
        memoryQueueSize = memoryQueueSize,
        executor = sdk.storageExecutor,
        storage = storage,
        dropped = dropped
    )
    private val uploader = TelemetryUploader(
        beacons = beacons,
        storage = storage,
        storageExecutor = sdk.storageExecutor,
        consentSource = sdk,
        networkSource = sdk.networkSource,
        clock = sdk.epochClock,
        dropped = dropped
    )

    fun subscribe() {
        sdk.exchangeBus.subscribe(collector)
        sdk.addDataOwner(collector)
    }

    fun start() {
        sdk.uploadExecutor.scheduleWithFixedDelay(
            uploader::upload,
            uploadPeriodMillis,
            uploadPeriodMillis,
            TimeUnit.MILLISECONDS
        )
    }
}
