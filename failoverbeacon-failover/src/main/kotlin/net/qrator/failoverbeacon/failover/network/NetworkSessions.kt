package net.qrator.failoverbeacon.failover.network

import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.failover.model.RefreshTimer

internal class NetworkSessions(
    private val networks: KnownNetworks,
    private val timer: RefreshTimer
) {
    @Volatile
    var network: DefaultNetwork? = null
        private set

    @Volatile
    var current: NetworkSession? = null
        private set

    @Synchronized
    fun endCurrent() {
        current?.end()
    }

    @Synchronized
    fun switchTo(network: DefaultNetwork?) {
        current?.end()
        this.network = network
        current = network?.let {
            NetworkSession(
                state = networks.of(it.key),
                timer = timer,
                networkHandle = it.handle
            )
        }
    }
}
