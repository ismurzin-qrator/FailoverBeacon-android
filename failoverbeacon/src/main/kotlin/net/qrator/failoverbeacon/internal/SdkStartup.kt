package net.qrator.failoverbeacon.internal

import android.app.Application
import android.content.Context
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.app.ForegroundSource
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.core.storage.EventStorage
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.internal.app.ForegroundMonitor
import net.qrator.failoverbeacon.internal.network.NetworkMonitor
import net.qrator.failoverbeacon.internal.time.ElapsedRealtimeEpochClock
import net.qrator.failoverbeacon.internal.time.NanoTimeClock
import net.qrator.failoverbeacon.telemetry.TelemetryCollector

internal object SdkStartup {
    fun start(
        appContext: Context,
        consent: TrackingConsent,
        config: FailoverBeaconConfig
    ): Boolean {
        val application = appContext as? Application
        val networkMonitor = NetworkMonitor(appContext)
        val foregroundMonitor = application?.let(::ForegroundMonitor)
        val epochClock = ElapsedRealtimeEpochClock()
        val sdk = SdkInstance(
            appContext = appContext,
            consent = consent,
            clock = NanoTimeClock,
            epochClock = epochClock,
            exchangeBus = ExchangeBus(),
            networkSource = networkMonitor,
            foregroundSource = foregroundMonitor ?: ForegroundSource { null },
            storageExecutor = SdkExecutor("storage", config.options.memoryQueueSize) {
                SdkRegistry.logger.warn(onlyOnce = true) {
                    "Memory queue is full. Events are dropped"
                }
            }
        )
        val storage = EventStorage(
            cacheDir = appContext::getCacheDir,
            beacons = config.beacons,
            clock = epochClock,
            batchWindowMillis = config.options.uploadPeriodMillis,
            maxBytes = config.options.diskQueueBytes
        )
        installFeatures(sdk, config, storage)
        if (!SdkRegistry.install(sdk)) return false
        networkMonitor.register()
        foregroundMonitor?.register()
        return true
    }

    private fun installFeatures(
        sdk: SdkInstance,
        config: FailoverBeaconConfig,
        storage: EventStorage
    ) {
        sdk.exchangeBus.subscribe(
            TelemetryCollector(
                consentSource = sdk,
                blockedHosts = config.blockedHosts,
                failureSampleRate = config.failureSampleRate,
                successSampleRate = config.successSampleRate,
                executor = sdk.storageExecutor,
                storage = storage
            )
        )
    }
}
