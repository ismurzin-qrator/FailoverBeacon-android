package net.qrator.failoverbeacon.core

import android.content.Context
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import net.qrator.failoverbeacon.core.app.ForegroundSource
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.network.NetworkSource
import net.qrator.failoverbeacon.core.time.EpochClock
import net.qrator.failoverbeacon.core.time.MonotonicClock

class SdkInstance(
    val appContext: Context,
    consent: TrackingConsent,
    val clock: MonotonicClock,
    val epochClock: EpochClock,
    val exchangeBus: ExchangeBus,
    val networkSource: NetworkSource,
    val foregroundSource: ForegroundSource,
    val storageExecutor: ExecutorService
) : ConsentSource,
    Closeable {
    @Volatile
    override var consent: TrackingConsent = consent
        private set

    private val dataOwners = CopyOnWriteArrayList<DataOwner>()

    fun addDataOwner(owner: DataOwner) {
        dataOwners += owner
    }

    fun applyConsent(consent: TrackingConsent) {
        this.consent = consent
        dataOwners.forEach { it.onConsentChanged(consent) }
    }

    fun clearData() {
        dataOwners.forEach { it.clearData() }
    }

    override fun close() {
        exchangeBus.close()
        storageExecutor.shutdown()
        networkSource.close()
        foregroundSource.close()
    }
}
