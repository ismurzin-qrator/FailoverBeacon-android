package net.qrator.failoverbeacon.core

import android.content.Context

/** The running SDK. */
class SdkInstance(val appContext: Context, @Volatile var consent: TrackingConsent)
