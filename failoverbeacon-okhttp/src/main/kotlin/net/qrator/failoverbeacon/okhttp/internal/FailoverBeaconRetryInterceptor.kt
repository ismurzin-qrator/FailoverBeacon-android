package net.qrator.failoverbeacon.okhttp.internal

import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.logging.info
import net.qrator.failoverbeacon.core.util.catchSdkFailure
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal class FailoverBeaconRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var next = chain
        if (isRetryControlAvailable) {
            catchSdkFailure(::stop) { if (isUnsafe(request)) next = withoutRetry(chain) }
        }
        return next.proceed(request)
    }

    private fun isUnsafe(request: Request): Boolean = request.method !in IDEMPOTENT_METHODS &&
        request.tag(Idempotent::class.java) == null &&
        SdkRegistry.instance?.dns?.manages(request.url.host) == true

    @SuppressSignatureCheck(since = "5.4")
    private fun withoutRetry(chain: Interceptor.Chain): Interceptor.Chain =
        if (chain.retryOnConnectionFailure) chain.withRetryOnConnectionFailure(false) else chain

    private fun stop(failure: Throwable) {
        if (failure is LinkageError) {
            isRetryControlAvailable = false
            SdkRegistry.logger.info {
                "[OkHttp] OkHttp before 5.4 resends unsafe requests after a connection failure"
            }
        } else {
            SdkRegistry.logger.error(failure, onlyOnce = true) {
                "[OkHttp] Retry check failed."
            }
        }
    }

    private companion object {
        // RFC 9110, section 9.2.2.
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE")

        @Volatile
        var isRetryControlAvailable = true
    }
}

internal object Idempotent
