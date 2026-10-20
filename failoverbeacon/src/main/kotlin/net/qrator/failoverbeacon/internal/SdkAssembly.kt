package net.qrator.failoverbeacon.internal

import android.content.Context
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.time.NanoTimeClock

internal object SdkAssembly {
    fun createInstance(appContext: Context, consent: TrackingConsent): SdkInstance = SdkInstance(
        appContext = appContext,
        consent = consent,
        clock = NanoTimeClock,
        exchangeBus = ExchangeBus()
    )
}
