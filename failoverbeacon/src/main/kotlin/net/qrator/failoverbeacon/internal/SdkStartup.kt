package net.qrator.failoverbeacon.internal

import android.content.Context
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.time.NanoTimeClock
import net.qrator.failoverbeacon.internal.network.NetworkMonitor
import net.qrator.failoverbeacon.telemetry.TelemetryCollector

internal object SdkStartup {
    fun start(
        appContext: Context,
        consent: TrackingConsent,
        config: FailoverBeaconConfig
    ): Boolean {
        val networkMonitor = NetworkMonitor(appContext)
        val sdk = SdkInstance(
            appContext = appContext,
            consent = consent,
            clock = NanoTimeClock,
            exchangeBus = ExchangeBus(),
            networkSource = networkMonitor
        )
        installFeatures(sdk, config)
        if (!SdkRegistry.install(sdk)) return false
        networkMonitor.register()
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
