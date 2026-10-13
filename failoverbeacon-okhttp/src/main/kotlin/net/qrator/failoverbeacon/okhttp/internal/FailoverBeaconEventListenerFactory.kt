package net.qrator.failoverbeacon.okhttp.internal

import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.CallRecorder
import okhttp3.Call
import okhttp3.EventListener

/** Adds the SDK listener to each call while the SDK is running. */
internal class FailoverBeaconEventListenerFactory(private val appFactory: EventListener.Factory) :
    EventListener.Factory {
    override fun create(call: Call): EventListener {
        val appListener = appFactory.create(call)
        val sdk = SdkRegistry.instance ?: return appListener
        if (!sdk.exchangeBus.hasSubscribers) return appListener
        if (CallRecordingEventListener.incompatible) return appListener
        val callRecorder = CallRecorder(sdk.clock, sdk.exchangeBus::publish)
        val sdkListener = CallRecordingEventListener(callRecorder)
        return combine(sdkListener, appListener)
    }

    @SuppressSignatureCheck(since = "5.3")
    private fun combine(first: EventListener, second: EventListener): EventListener {
        if (second === EventListener.NONE) return first
        if (plusAvailable) {
            try {
                return first + second
            } catch (_: NoSuchMethodError) {
                plusAvailable = false
            }
        }
        return CompositeEventListener(first, second)
    }

    private companion object {
        @Volatile
        var plusAvailable = true
    }
}
