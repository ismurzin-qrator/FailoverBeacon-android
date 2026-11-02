package net.qrator.failoverbeacon.failover.network

import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.RefreshTimer

internal class NetworkSessions(
    private val hostConfigs: List<FailoverHostConfig>,
    private val timer: RefreshTimer,
    private val maxNetworks: Int
) {
    private val hostStatesByNetwork = LinkedHashMap<String, HostStates>()
    private var firstHandle: Long? = null

    @Volatile
    var current: NetworkSession? = null
        private set

    @Synchronized
    fun switchTo(network: DefaultNetwork?) {
        current?.end()
        if (firstHandle == null) firstHandle = network?.handle
        current = network?.let {
            NetworkSession(it.handle == firstHandle, hostStatesOf(it.key), timer)
        }
    }

    private fun hostStatesOf(networkKey: String): HostStates {
        val states = hostStatesByNetwork.remove(networkKey) ?: HostStates(hostConfigs)
        hostStatesByNetwork[networkKey] = states
        if (hostStatesByNetwork.size > maxNetworks) {
            hostStatesByNetwork.remove(hostStatesByNetwork.keys.first())
        }
        return states
    }
}
