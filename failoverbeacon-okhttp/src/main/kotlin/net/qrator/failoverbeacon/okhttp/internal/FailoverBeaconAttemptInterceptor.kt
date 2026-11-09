package net.qrator.failoverbeacon.okhttp.internal

import java.net.Proxy
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response

internal class FailoverBeaconAttemptInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val attempt = chain.request().tag(NetworkAttempt::class.java)
        val connection = chain.connection()
        if (attempt != null && connection != null) {
            val address = connection.route().takeIf {
                it.proxy.type() == Proxy.Type.DIRECT
            }?.socketAddress?.address
            if (address != null && address == attempt.avoidedAddress) throw SameAddressException()
            attempt.directHttp2Address = address?.takeIf { connection.protocol() in HTTP_2 }
        }
        return chain.proceed(chain.request())
    }

    private companion object {
        val HTTP_2 = setOf(Protocol.HTTP_2, Protocol.H2_PRIOR_KNOWLEDGE)
    }
}
