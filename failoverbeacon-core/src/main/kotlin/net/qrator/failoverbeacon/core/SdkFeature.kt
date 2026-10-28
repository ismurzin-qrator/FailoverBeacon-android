package net.qrator.failoverbeacon.core

import java.io.Closeable

interface SdkFeature : Closeable {
    fun onConsentChanged(consent: TrackingConsent) {}

    fun clearData() {}

    override fun close() {}
}
