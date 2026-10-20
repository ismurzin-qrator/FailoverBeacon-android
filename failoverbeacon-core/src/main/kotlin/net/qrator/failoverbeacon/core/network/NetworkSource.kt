package net.qrator.failoverbeacon.core.network

import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot

fun interface NetworkSource {
    fun currentNetwork(): NetworkSnapshot
}
