package net.qrator.failoverbeacon.okhttp.fakes

import android.content.ContextWrapper
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot

/** Installs an SDK whose exchanges go to [subscribers]; without one, calls are not recorded. */
fun installSdk(
    vararg subscribers: ExchangeSubscriber,
    network: NetworkSnapshot = NetworkSnapshot.Unknown,
    foreground: Boolean? = null
) {
    val bus = ExchangeBus()
    subscribers.forEach(bus::subscribe)
    SdkRegistry.install(
        SdkInstance(
            appContext = ContextWrapper(null),
            consent = TrackingConsent.GRANTED,
            clock = { System.nanoTime() },
            exchangeBus = bus,
            networkSource = { network },
            foregroundSource = { foreground }
        )
    )
}
