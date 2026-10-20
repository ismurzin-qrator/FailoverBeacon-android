package net.qrator.failoverbeacon.internal.network.mapper

import android.net.NetworkCapabilities
import net.qrator.failoverbeacon.core.network.model.NetworkTransport

internal class NetworkTransportMapper {
    fun toTransport(capabilities: NetworkCapabilities): NetworkTransport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI

        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            NetworkTransport.ETHERNET

        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            NetworkTransport.CELLULAR

        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ->
            NetworkTransport.BLUETOOTH

        else -> NetworkTransport.OTHER
    }
}
