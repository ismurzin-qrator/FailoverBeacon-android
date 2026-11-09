package net.qrator.failoverbeacon.failover.network

import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.failover.model.MaxAge

internal class NetworkSessions(private val networks: KnownNetworks, private val maxAge: MaxAge) {
    private var network: DefaultNetwork? = null

    @Volatile
    var current: NetworkSession? = null
        private set

    fun endCurrent() {
        current?.end()
    }

    fun switchTo(network: DefaultNetwork?) {
        current?.end()
        this.network = network
        current = network?.let {
            NetworkSession(
                hosts = networks.of(it.key),
                maxAge = maxAge,
                networkHandle = it.handle
            )
        }
    }

    fun restart() = switchTo(network)
}
