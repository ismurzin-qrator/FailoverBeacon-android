package net.qrator.failoverbeacon.okhttp.internal

import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.CallRecorder
import net.qrator.failoverbeacon.okhttp.internal.mapper.OkHttpFailureMapper
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Call
import okhttp3.EventListener

/** Adds the SDK listener to each call while the SDK is running. */
internal class FailoverBeaconEventListenerFactory(private val appFactory: EventListener.Factory) :
    EventListener.Factory {
    private val failureMapper = OkHttpFailureMapper()

    override fun create(call: Call): EventListener {
        val appListener = appFactory.create(call)
        val sdk = SdkRegistry.instance ?: return appListener
        if (!sdk.exchangeBus.hasSubscribers) return appListener
        if (CallRecordingEventListener.incompatible) return appListener
        val callRecorder = CallRecorder(
            clock = sdk.clock,
            epochClock = sdk.epochClock,
            networkSource = sdk.networkSource,
            foregroundSource = sdk.foregroundSource,
            failureMapper = failureMapper,
            publisher = sdk.exchangeBus
        )
        val sdkListener = CallRecordingEventListener(callRecorder)
        return combine(sdkListener, appListener)
    }

    private fun combine(first: EventListener, second: EventListener): EventListener {
        if (second === EventListener.NONE) return first
        if (plusAvailable) {
            try {
                return plus(first, second)
            } catch (_: NoSuchMethodError) {
                plusAvailable = false
            }
        }
        return CompositeEventListener(first, second)
    }

    @SuppressSignatureCheck(since = "5.3")
    private fun plus(first: EventListener, second: EventListener): EventListener = first + second

    private companion object {
        @Volatile
        var plusAvailable = true
    }
}
