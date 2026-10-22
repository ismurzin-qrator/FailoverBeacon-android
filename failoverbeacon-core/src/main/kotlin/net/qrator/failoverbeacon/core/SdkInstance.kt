package net.qrator.failoverbeacon.core

import android.content.Context
import java.io.Closeable
import java.util.concurrent.ExecutorService
import net.qrator.failoverbeacon.core.app.ForegroundSource
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.network.NetworkSource
import net.qrator.failoverbeacon.core.time.EpochClock
import net.qrator.failoverbeacon.core.time.MonotonicClock

class SdkInstance(
    val appContext: Context,
    @Volatile override var consent: TrackingConsent,
    val clock: MonotonicClock,
    val epochClock: EpochClock,
    val exchangeBus: ExchangeBus,
    val networkSource: NetworkSource,
    val foregroundSource: ForegroundSource,
    val storageExecutor: ExecutorService
) : ConsentSource,
    Closeable {
    override fun close() {
        exchangeBus.close()
        storageExecutor.shutdownNow()
        networkSource.close()
        foregroundSource.close()
    }
}
