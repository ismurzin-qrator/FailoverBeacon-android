package net.qrator.failoverbeacon.internal

import android.app.Application
import android.content.Context
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.app.ForegroundSource
import net.qrator.failoverbeacon.core.call.ExchangeBus
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
        val sdk = SdkInstance(
            appContext = appContext,
            consent = consent,
            clock = NanoTimeClock,
            epochClock = ElapsedRealtimeEpochClock(),
            exchangeBus = ExchangeBus(),
            networkSource = networkMonitor,
            foregroundSource = foregroundMonitor ?: ForegroundSource { null }
        )
        installFeatures(sdk, config)
        if (!SdkRegistry.install(sdk)) return false
        networkMonitor.register()
        foregroundMonitor?.register()
        return true
    }

    private fun installFeatures(sdk: SdkInstance, config: FailoverBeaconConfig) {
        sdk.exchangeBus.subscribe(
            TelemetryCollector(
                consentSource = sdk,
                blockedHosts = config.blockedHosts,
                failureSampleRate = config.failureSampleRate,
                successSampleRate = config.successSampleRate
            )
        )
    }
}
