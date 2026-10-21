package net.qrator.failoverbeacon.internal.network.state

import android.net.NetworkCapabilities
import net.qrator.failoverbeacon.core.network.model.NetworkCarrier

internal class NetworkState {
    var capabilities: NetworkCapabilities? = null
    var carrier: NetworkCarrier? = null
    var blocked: Boolean? = null
}
