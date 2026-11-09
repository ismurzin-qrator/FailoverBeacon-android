package net.qrator.failoverbeacon.okhttp.internal

import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.logging.info
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal class FailoverBeaconRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isRetriable(chain)) return chain.proceed(request)
        if (!isIdempotent(request)) return withoutRetry(chain).proceed(request)
        return proceedWithTimeoutRetry(chain, request)
    }

    @SuppressSignatureCheck(since = "5.4")
    private fun isRetriable(chain: Interceptor.Chain): Boolean {
        if (!isRetryControlAvailable) return false
        return try {
            val isManaged = SdkRegistry.instance?.dns?.manages(chain.request().url.host)
            isManaged == true && chain.retryOnConnectionFailure
        } catch (_: LinkageError) {
            isRetryControlAvailable = false
            SdkRegistry.logger.info {
                "[OkHttp] OkHttp before 5.4 resends unsafe requests after a connection failure"
            }
            false
        } catch (e: Exception) {
            SdkRegistry.logger.error(e, onlyOnce = true) { "[OkHttp] Retry check failed." }
            false
        }
    }

    private fun isIdempotent(request: Request): Boolean =
        request.method in IDEMPOTENT_METHODS || request.tag(Idempotent::class.java) != null

    private fun proceedWithTimeoutRetry(chain: Interceptor.Chain, request: Request): Response {
        val attempt = NetworkAttempt()
        val tagged = request.newBuilder().tag(NetworkAttempt::class.java, attempt).build()
        try {
            return chain.proceed(tagged)
        } catch (timeout: SocketTimeoutException) {
            val timedOutAddress = attempt.directHttp2Address
            if (timedOutAddress == null || request.body?.isOneShot() == true) throw timeout

            val call = chain.call()
            val deadline = System.nanoTime() + PING_DEADLINE_NANOS
            try {
                while (System.nanoTime() < deadline && !call.isCanceled()) Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw timeout
            }
            if (call.isCanceled()) throw timeout

            attempt.avoidedAddress = timedOutAddress
            try {
                // Without OkHttp's own retry, SameAddressException ends the call.
                return withoutRetry(chain).proceed(tagged)
            } catch (_: SameAddressException) {
                throw timeout
            }
        }
    }

    @SuppressSignatureCheck(since = "5.4")
    private fun withoutRetry(chain: Interceptor.Chain): Interceptor.Chain =
        chain.withRetryOnConnectionFailure(false)

    private companion object {
        // RFC 9110, section 9.2.2.
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE")

        // OkHttp drops a timed-out HTTP/2 connection once its ping is 1 s overdue
        // (Http2Connection.DEGRADED_PONG_TIMEOUT_NS).
        val PING_DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(1000 + 100)
        const val POLL_MILLIS = 50L

        @Volatile
        var isRetryControlAvailable = true
    }
}
