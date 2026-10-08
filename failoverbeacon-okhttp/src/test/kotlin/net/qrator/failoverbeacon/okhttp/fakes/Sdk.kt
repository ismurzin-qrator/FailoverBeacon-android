package net.qrator.failoverbeacon.okhttp.fakes

import android.content.ContextWrapper
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent

fun installSdk() {
    SdkRegistry.install(SdkInstance(ContextWrapper(null), TrackingConsent.GRANTED))
}
