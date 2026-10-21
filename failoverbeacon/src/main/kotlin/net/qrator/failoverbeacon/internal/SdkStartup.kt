package net.qrator.failoverbeacon.internal

import android.app.Application
import android.content.Context
import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.app.ForegroundSource
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.time.NanoTimeClock
import net.qrator.failoverbeacon.internal.app.ForegroundMonitor
import net.qrator.failoverbeacon.internal.network.NetworkMonitor
import net.qrator.failoverbeacon.telemetry.TelemetryCollector

internal object SdkStartup {
    fun start(
        appContext: Context,
        consent: TrackingConsent,
        config: FailoverBeaconConfig
    ): Boolean {
        val application = appContext as? Application
        val networkMonitor = NetworkMonitor(appContext)
        val foregroundMonitor = ForegroundMonitor()
        val foregroundSource =
            if (application != null) foregroundMonitor else ForegroundSource { null }
        val sdk = SdkInstance(
            appContext = appContext,
            consent = consent,
            clock = NanoTimeClock,
            exchangeBus = ExchangeBus(),
            networkSource = networkMonitor,
            foregroundSource = foregroundSource
        )
        installFeatures(sdk, config)
        if (!SdkRegistry.install(sdk)) return false
        networkMonitor.register()
        application?.registerActivityLifecycleCallbacks(foregroundMonitor)
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
