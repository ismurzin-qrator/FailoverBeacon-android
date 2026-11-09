package net.qrator.failoverbeacon.failover.network

import net.qrator.failoverbeacon.core.util.sha256Hex
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostState

internal class KnownNetworks(
    private val hostConfigs: List<FailoverHostConfig>,
    private val maxNetworks: Int
) {
    private val hostsByKey = LinkedHashMap<String, NetworkHosts>()

    // A subnet or a mobile operator must not reach the disk in plain text.
    fun of(networkKey: String): NetworkHosts {
        val key = sha256Hex(networkKey)
        val hosts = hostsByKey.remove(key) ?: newHosts(emptyList())
        hostsByKey[key] = hosts
        if (hostsByKey.size > maxNetworks) {
            hostsByKey.remove(hostsByKey.keys.first())
        }
        return hosts
    }

    fun all(): Map<String, List<HostState>> = hostsByKey.mapValues { it.value.all }

    fun putAll(networks: Map<String, List<HostState>>) {
        networks.forEach { (key, hosts) -> hostsByKey[key] = newHosts(hosts) }
    }

    fun clear() {
        hostsByKey.clear()
    }

    private fun newHosts(known: List<HostState>): NetworkHosts {
        val knownByHost = known.associateBy { it.config.host }
        return NetworkHosts(hostConfigs.map { knownByHost[it.host] ?: HostState(it) })
    }
}
