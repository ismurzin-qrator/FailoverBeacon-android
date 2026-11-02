package net.qrator.failoverbeacon.failover.network

import java.util.concurrent.ConcurrentHashMap
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostState

internal class HostStates(configs: List<FailoverHostConfig>) {
    private val names = configs.map { it.host }
    private val states = ConcurrentHashMap(configs.associate { it.host to HostState(it) })

    val all: List<HostState> get() = names.mapNotNull(states::get)

    operator fun get(host: String): HostState? = states[host]

    fun update(host: String, change: (HostState) -> HostState) {
        states.computeIfPresent(host) { _, state -> change(state) }
    }
}
