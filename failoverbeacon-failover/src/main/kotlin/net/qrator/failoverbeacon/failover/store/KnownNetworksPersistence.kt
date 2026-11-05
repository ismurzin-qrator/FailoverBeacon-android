package net.qrator.failoverbeacon.failover.store

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.qrator.failoverbeacon.core.time.EpochClock
import net.qrator.failoverbeacon.failover.mapper.SavedHostMapper
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.model.SavedNetwork
import net.qrator.failoverbeacon.failover.network.KnownNetworks

internal class KnownNetworksPersistence(
    cacheDir: () -> File,
    private val networks: KnownNetworks,
    hostConfigs: List<FailoverHostConfig>,
    clock: EpochClock,
    bodyProbeTimer: RefreshTimer,
    private val storage: Executor,
    private val scheduler: ScheduledExecutorService
) {
    private val store = SavedNetworksStore(cacheDir)
    private val mapper = SavedHostMapper(hostConfigs, clock, bodyProbeTimer)
    private val isSaveScheduled = AtomicBoolean()

    fun restore() = storage.execute {
        networks.putAll(
            store.read().associate { network ->
                network.key to network.hosts.mapNotNull(mapper::toState)
            }
        )
    }

    fun saveLater() {
        if (isSaveScheduled.compareAndSet(false, true)) {
            val save = Runnable { storage.execute(::save) }
            scheduler.schedule(save, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    fun clear() = storage.execute {
        store.clear()
        networks.clear()
    }

    private fun save() {
        isSaveScheduled.set(false)
        store.write(
            networks.all()
                .map { (key, hosts) -> SavedNetwork(key, hosts.mapNotNull(mapper::toSaved)) }
                .filter { it.hosts.isNotEmpty() }
        )
    }

    private companion object {
        const val SAVE_DELAY_MILLIS = 20_000L
    }
}
