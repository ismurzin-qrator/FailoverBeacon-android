package net.qrator.failoverbeacon.failover.network

import net.qrator.failoverbeacon.core.util.sha256Hex
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostState

internal class KnownNetworks(
    private val hostConfigs: List<FailoverHostConfig>,
    private val maxNetworks: Int
) {
    private val statesByKey = LinkedHashMap<String, NetworkState>()

    // A subnet or a mobile operator must not reach the disk in plain text.
    @Synchronized
    fun of(networkKey: String): NetworkState {
        val key = sha256Hex(networkKey)
        val state = statesByKey.remove(key) ?: newState(emptyList())
        statesByKey[key] = state
        if (statesByKey.size > maxNetworks) {
            statesByKey.remove(statesByKey.keys.first())
        }
        return state
    }

    @Synchronized
    fun all(): Map<String, List<HostState>> = statesByKey.mapValues { it.value.hosts }

    @Synchronized
    fun putAll(networks: Map<String, List<HostState>>) {
        networks.forEach { (key, hosts) -> statesByKey[key] = newState(hosts) }
    }

    @Synchronized
    fun clear() {
        statesByKey.clear()
    }

    private fun newState(known: List<HostState>): NetworkState {
        val knownByHost = known.associateBy { it.config.host }
        return NetworkState(hostConfigs.map { knownByHost[it.host] ?: HostState(it) })
    }
}
