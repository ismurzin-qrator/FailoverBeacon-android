package net.qrator.failoverbeacon.core

import java.io.Closeable
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork

interface SdkFeature : Closeable {
    fun onConsentChanged(consent: TrackingConsent) {}

    fun onNetworkChanged(network: DefaultNetwork?) {}

    fun clearData() {}

    override fun close() {}
}
