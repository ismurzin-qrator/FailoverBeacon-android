package net.qrator.failoverbeacon.telemetry

import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.telemetry.storage.EventStorage
import net.qrator.failoverbeacon.telemetry.upload.TelemetryUploader
import net.qrator.failoverbeacon.telemetry.upload.appVersionName

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
        executor = sdk.threads.serial,
        storage = storage,
        dropped = dropped
    )
    private val uploader = TelemetryUploader(
        beacons = beacons,
        storage = storage,
        storageExecutor = sdk.threads.serial,
        consentSource = sdk,
        networkSource = sdk.networkSource,
        clock = sdk.epochClock,
        dropped = dropped,
        appPackage = sdk.appContext.packageName,
        appVersion = sdk.appContext::appVersionName
    )

    fun subscribe() {
        sdk.exchangeBus.subscribe(collector)
        sdk.addFeature(this)
    }

    fun start() {
        sdk.threads.scheduler.scheduleWithFixedDelay(
            uploader::upload,
            0,
            uploadPeriodMillis,
            TimeUnit.MILLISECONDS
        )
    }

    override fun onConsentChanged(consent: TrackingConsent) = collector.onConsentChanged(consent)

    override fun clearData() = collector.clearData()

    override fun close() = uploader.stop()

    private companion object {
        const val MAX_BATCH_WINDOW_MILLIS = 10_000L
    }
}
