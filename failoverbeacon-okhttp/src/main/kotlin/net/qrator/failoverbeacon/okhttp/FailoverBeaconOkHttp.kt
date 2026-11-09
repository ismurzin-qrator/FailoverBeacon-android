@file:JvmName("FailoverBeaconOkHttp")

package net.qrator.failoverbeacon.okhttp

import net.qrator.failoverbeacon.FailoverBeacon
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconAttemptInterceptor
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconDns
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconDnsFactory
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconEventListenerFactory
import net.qrator.failoverbeacon.okhttp.internal.FailoverBeaconRetryInterceptor
import net.qrator.failoverbeacon.okhttp.internal.Idempotent
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Returns a copy of this client configured with the FailoverBeacon SDK.
 *
 * Shares the connection pool, dispatcher, and event listener with the original client.
 * Failover hosts receive SDK-provided addresses, while non-failover hosts use the client's DNS.
 * Requires OkHttp 3.14 or newer.
 */
public fun OkHttpClient.withFailoverBeacon(): OkHttpClient {
    val hasSdkListener = eventListenerFactory is FailoverBeaconEventListenerFactory
    val hasSdkDns = dns is FailoverBeaconDns
    val hasSdkInterceptor = interceptors.any { it is FailoverBeaconRetryInterceptor }
    val hasSdkNetworkInterceptor = networkInterceptors.any {
        it is FailoverBeaconAttemptInterceptor
    }
    if (hasSdkListener && hasSdkDns && hasSdkInterceptor && hasSdkNetworkInterceptor) return this
    return newBuilder().apply {
        if (!hasSdkListener) {
            eventListenerFactory(FailoverBeaconEventListenerFactory(eventListenerFactory))
        }
        if (!hasSdkDns) dns(FailoverBeaconDnsFactory.create(dns))
        if (!hasSdkInterceptor) addInterceptor(FailoverBeaconRetryInterceptor())
        if (!hasSdkNetworkInterceptor) addNetworkInterceptor(FailoverBeaconAttemptInterceptor())
    }.build()
}

/**
 * Marks the request as idempotent, so it may be sent again after a connection failure or timeout.
 *
 * GET, HEAD, OPTIONS, TRACE, PUT, and DELETE methods are treated as idempotent by default.
 */
public fun Request.Builder.idempotent(): Request.Builder = tag(Idempotent::class.java, Idempotent)
