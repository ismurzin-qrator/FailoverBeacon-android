package net.qrator.failoverbeacon.internal

import android.app.Application
import android.content.Context
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.app.ForegroundSource
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.failover.Failover
import net.qrator.failoverbeacon.failover.FailoverSettings
import net.qrator.failoverbeacon.internal.app.ForegroundMonitor
import net.qrator.failoverbeacon.internal.mapper.FailoverHostMapper
import net.qrator.failoverbeacon.internal.network.NetworkMonitor
import net.qrator.failoverbeacon.internal.time.ElapsedRealtimeClock
import net.qrator.failoverbeacon.internal.time.ElapsedRealtimeEpochClock
import net.qrator.failoverbeacon.internal.time.NanoTimeClock
import net.qrator.failoverbeacon.telemetry.Telemetry

internal object SdkStartup {
    private val hostMapper = FailoverHostMapper()

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
            elapsedRealtimeClock = ElapsedRealtimeClock,
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
            hostConfigs = config.failoverHosts.map(hostMapper::toConfig),
            queryDns = config.dnsResolver::resolve,
            settings = FailoverSettings(
                preresolveOnNetworkChange = config.options.preresolveOnNetworkChange,
                probeOnNetworkChange = config.options.probeOnNetworkChange,
                refreshIntervalNanos =
                    TimeUnit.MILLISECONDS.toNanos(config.options.refreshIntervalMillis)
            )
        )
        failover.subscribe()
        if (!SdkRegistry.install(sdk)) return false
        try {
            networkMonitor.register(sdk::publishNetwork)
            foregroundMonitor?.register()
            telemetry.start()
            sdk.publishConsent()
        } catch (e: Throwable) {
            SdkRegistry.clear()
            throw e
        }
        return true
    }
}
