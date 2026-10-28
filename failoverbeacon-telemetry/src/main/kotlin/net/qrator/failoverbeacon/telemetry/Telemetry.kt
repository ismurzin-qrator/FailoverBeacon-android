package net.qrator.failoverbeacon.telemetry

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.core.thread.SdkScheduledExecutor
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
) : SdkFeature {
    val storageExecutor = SdkExecutor(
        name = "storage",
        coreThreads = 1,
        maxThreads = 1,
        keepAliveMillis = 0,
        queue = LinkedBlockingQueue()
    )
    val uploadExecutor = SdkScheduledExecutor("upload")
    private val dropped = DroppedEvents(beacons)
    private val storage = EventStorage(
        cacheDir = sdk.appContext::getCacheDir,
        beacons = beacons,
        clock = sdk.epochClock,
        batchWindowMillis = minOf(uploadPeriodMillis, MAX_BATCH_WINDOW_MILLIS),
        maxBytes = diskQueueBytes,
        dropped = dropped
    )
    private val collector = TelemetryCollector(
        consentSource = sdk,
        blockedHosts = blockedHosts,
        failureSampleRate = failureSampleRate,
        successSampleRate = successSampleRate,
        memoryQueueSize = memoryQueueSize,
        executor = storageExecutor,
        storage = storage,
        dropped = dropped
    )
    private val uploader = TelemetryUploader(
        beacons = beacons,
        storage = storage,
        storageExecutor = storageExecutor,
        consentSource = sdk,
        networkSource = sdk.networkSource,
        clock = sdk.epochClock,
        dropped = dropped
    )

    fun subscribe() {
        sdk.exchangeBus.subscribe(collector)
        sdk.addFeature(this)
    }

    fun start() {
        uploadExecutor.scheduleWithFixedDelay(
            uploader::upload,
            0,
            uploadPeriodMillis,
            TimeUnit.MILLISECONDS
        )
    }

    override fun onConsentChanged(consent: TrackingConsent) = collector.onConsentChanged(consent)

    override fun clearData() = collector.clearData()

    override fun close() {
        storageExecutor.shutdown()
        uploadExecutor.shutdownNow()
    }

    private companion object {
        const val MAX_BATCH_WINDOW_MILLIS = 10_000L
    }
}
