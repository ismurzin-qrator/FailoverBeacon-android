package net.qrator.failoverbeacon.core.network

import java.io.Closeable
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot

fun interface NetworkSource : Closeable {
    fun currentNetwork(): NetworkSnapshot

    override fun close() = Unit
}
