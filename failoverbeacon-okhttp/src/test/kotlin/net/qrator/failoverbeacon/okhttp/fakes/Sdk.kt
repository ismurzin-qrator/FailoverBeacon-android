package net.qrator.failoverbeacon.okhttp.fakes

import android.content.ContextWrapper
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.ExchangeBus

fun installSdk() {
    SdkRegistry.install(
        SdkInstance(
            appContext = ContextWrapper(null),
            consent = TrackingConsent.GRANTED,
            clock = { 0 },
            exchangeBus = ExchangeBus(SdkRegistry.logger)
        )
    )
}
