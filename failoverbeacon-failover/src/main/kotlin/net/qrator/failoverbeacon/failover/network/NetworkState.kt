package net.qrator.failoverbeacon.failover.network

import java.util.concurrent.ConcurrentHashMap
import net.qrator.failoverbeacon.failover.model.HostState

internal class NetworkState(initial: List<HostState>) {
    private val names = initial.map { it.config.host }
    private val states = ConcurrentHashMap(initial.associateBy { it.config.host })

    val hosts: List<HostState> get() = names.mapNotNull(states::get)

    operator fun get(host: String): HostState? = states[host]

    fun update(host: String, change: (HostState) -> HostState) {
        states.computeIfPresent(host) { _, state -> change(state) }
    }
}
