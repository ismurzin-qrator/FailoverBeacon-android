@file:JvmName("FailoverBeaconOkHttp")

package net.qrator.failoverbeacon.okhttp

import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconDns
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconDnsFactory
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconEventListenerFactory
import okhttp3.OkHttpClient

/**
 * Returns a copy of this client observed by the FailoverBeacon SDK. The copy shares the connection
 * pool and dispatcher with this client, and this client's [okhttp3.EventListener] still receives
 * every event. Hosts from `FailoverBeaconConfig.Builder.failoverHosts` get addresses from the
 * SDK, fastest first, other hosts use this client's DNS. SDK failures don't affect calls. Observes
 * nothing until [FailoverBeacon.initialize] is called.
 *
 * Requires OkHttp 3.14 or newer.
 */
public fun OkHttpClient.withFailoverBeacon(): OkHttpClient {
    val hasSdkListener = eventListenerFactory is FailoverBeaconEventListenerFactory
    val hasSdkDns = dns is FailoverBeaconDns
    if (hasSdkListener && hasSdkDns) return this
    return newBuilder().apply {
        if (!hasSdkListener) {
            eventListenerFactory(FailoverBeaconEventListenerFactory(eventListenerFactory))
        }
        if (!hasSdkDns) dns(FailoverBeaconDnsFactory.create(dns))
    }.build()
}
