package net.qrator.failoverbeacon.core

import android.content.Context
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import net.qrator.failoverbeacon.core.app.ForegroundSource
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.network.NetworkSource
import net.qrator.failoverbeacon.core.thread.SdkThreads
import net.qrator.failoverbeacon.core.time.EpochClock
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.core.util.catchSdkFailure

class SdkInstance(
    val appContext: Context,
    consent: TrackingConsent,
    val clock: MonotonicClock,
    val elapsedRealtimeClock: MonotonicClock,
    val epochClock: EpochClock,
    val exchangeBus: ExchangeBus,
    val networkSource: NetworkSource,
    val foregroundSource: ForegroundSource
) : ConsentSource,
    Closeable {
    @Volatile
    override var consent: TrackingConsent = consent
        private set

    @Volatile
    var dns: SdkDns? = null

    val threads = SdkThreads()

    private val featureList = CopyOnWriteArrayList<SdkFeature>()

    fun addFeature(feature: SdkFeature) {
        featureList += feature
    }

    fun applyConsent(consent: TrackingConsent) {
        this.consent = consent
        publishConsent()
    }

    fun publishConsent() {
        val consent = consent
        featureList.forEach { it.onConsentChanged(consent) }
    }

    fun clearData() {
        featureList.forEach { it.clearData() }
    }

    override fun close() {
        exchangeBus.close()
        featureList.forEach { feature ->
            catchSdkFailure(::logStopFailure) { feature.close() }
        }
        threads.close()
        networkSource.close()
        foregroundSource.close()
    }

    private fun logStopFailure(failure: Throwable) {
        SdkRegistry.logger.error(failure) { "[Core] Cannot stop an SDK feature" }
    }
}
