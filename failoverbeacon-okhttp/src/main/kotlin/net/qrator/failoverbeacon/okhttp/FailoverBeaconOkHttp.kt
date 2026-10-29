@file:JvmName("FailoverBeaconOkHttp")

package net.qrator.failoverbeacon.okhttp

import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconDnsFactory
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconEventListenerFactory
import okhttp3.OkHttpClient

/**
 * Returns a copy of this client observed by the FailoverBeacon SDK. The copy shares the connection
 * pool and dispatcher with this client, and this client's [okhttp3.EventListener] still receives
 * every event. SDK failures don't affect calls. Observes nothing until [FailoverBeacon.initialize]
 * is called.
 *
 * Requires OkHttp 3.14 or newer.
 */
public fun OkHttpClient.withFailoverBeacon(): OkHttpClient {
    if (eventListenerFactory is FailoverBeaconEventListenerFactory) return this
    return newBuilder()
        .eventListenerFactory(FailoverBeaconEventListenerFactory(eventListenerFactory))
        .dns(FailoverBeaconDnsFactory.create(dns))
        .build()
}
