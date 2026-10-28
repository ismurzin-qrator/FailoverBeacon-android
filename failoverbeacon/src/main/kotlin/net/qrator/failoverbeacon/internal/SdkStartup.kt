package net.qrator.failoverbeacon.internal

import android.app.Application
import android.content.Context
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.app.ForegroundSource
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.failover.Failover
import net.qrator.failoverbeacon.failover.FailoverSettings
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.internal.app.ForegroundMonitor
import net.qrator.failoverbeacon.internal.network.NetworkMonitor
import net.qrator.failoverbeacon.internal.time.ElapsedRealtimeEpochClock
import net.qrator.failoverbeacon.internal.time.NanoTimeClock
import net.qrator.failoverbeacon.telemetry.Telemetry

internal object SdkStartup {
    fun start(
        appContext: Context,
        consent: TrackingConsent,
        config: FailoverBeaconConfig
    ): Boolean {
        val application = appContext as? Application
        val networkMonitor = NetworkMonitor(appContext)
        val foregroundMonitor = application?.let(::ForegroundMonitor)
        val sdk = SdkInstance(
            appContext = appContext,
            consent = consent,
            clock = NanoTimeClock,
            epochClock = ElapsedRealtimeEpochClock(),
            exchangeBus = ExchangeBus(),
            networkSource = networkMonitor,
            foregroundSource = foregroundMonitor ?: ForegroundSource { null }
        )
        val telemetry = Telemetry(
            sdk = sdk,
            beacons = config.beacons,
            blockedHosts = config.blockedHosts,
            failureSampleRate = config.failureSampleRate,
            successSampleRate = config.successSampleRate,
            memoryQueueSize = config.options.memoryQueueSize,
            uploadPeriodMillis = config.options.uploadPeriodMillis,
            diskQueueBytes = config.options.diskQueueBytes
        )
        telemetry.subscribe()
        val failover = Failover(
            sdk = sdk,
            hosts = config.failoverHosts.map {
                FailoverHostConfig(it.host, it.additionalAddresses)
            },
            settings = FailoverSettings()
        )
        failover.subscribe()
        if (!SdkRegistry.install(sdk)) return false
        try {
            networkMonitor.register()
            foregroundMonitor?.register()
            telemetry.start()
            failover.start()
            sdk.publishConsent()
        } catch (e: Throwable) {
            SdkRegistry.clear()
            throw e
        }
        return true
    }
}
