package net.qrator.failoverbeacon.core

import android.content.Context
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.time.MonotonicClock

/** The running SDK. */
class SdkInstance(
    val appContext: Context,
    @Volatile override var consent: TrackingConsent,
    val clock: MonotonicClock,
    val exchangeBus: ExchangeBus
) : ConsentSource
