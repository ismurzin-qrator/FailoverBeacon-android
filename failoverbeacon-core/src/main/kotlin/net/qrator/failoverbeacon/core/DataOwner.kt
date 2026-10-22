package net.qrator.failoverbeacon.core

interface DataOwner {
    fun onConsentChanged(consent: TrackingConsent)

    fun clearData()
}
