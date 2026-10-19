package net.qrator.failoverbeacon.telemetry

import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.TrackingConsent

class TestConsentSource(override var consent: TrackingConsent) : ConsentSource
