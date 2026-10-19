package net.qrator.failoverbeacon.internal

import net.qrator.failoverbeacon.FailoverBeaconConfig
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.telemetry.TelemetryCollector

internal fun installFeatures(sdk: SdkInstance, config: FailoverBeaconConfig) {
    sdk.exchangeBus.subscribe(
        TelemetryCollector(
            consentSource = sdk,
            blockedHosts = config.blockedHosts,
            failureSampleRate = config.failureSampleRate,
            successSampleRate = config.successSampleRate
        )
    )
}
