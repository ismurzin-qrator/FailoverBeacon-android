package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * Sends each event of a call to [delegate] and keeps its exceptions and linkage errors from the
 * call. After the first failure, [delegate] gets no further events of this call. Only the first
 * failure in the process is logged, later ones only stop the delegate of their call.
 */
internal class FailureIsolatingEventListener(delegate: EventListener) : EventListener() {
    @Volatile
    private var delegate: EventListener? = delegate

    override fun callStart(call: Call) = isolate { it.callStart(call) }

    @SuppressSignatureCheck(since = "5.2")
    override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) =
        isolate { it.dispatcherQueueStart(call, dispatcher) }

    @SuppressSignatureCheck(since = "5.2")
    override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) =
        isolate { it.dispatcherQueueEnd(call, dispatcher) }

    @SuppressSignatureCheck(since = "4.1")
    override fun proxySelectStart(call: Call, url: HttpUrl) =
        isolate { it.proxySelectStart(call, url) }

    @SuppressSignatureCheck(since = "4.1")
    override fun proxySelectEnd(
        call: Call,
        url: HttpUrl,
        proxies: List<@JvmSuppressWildcards Proxy>
    ) = isolate { it.proxySelectEnd(call, url, proxies) }

    override fun dnsStart(call: Call, domainName: String) = isolate {
        it.dnsStart(call, domainName)
    }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<@JvmSuppressWildcards InetAddress>
    ) = isolate { it.dnsEnd(call, domainName, inetAddressList) }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        isolate { it.connectStart(call, inetSocketAddress, proxy) }

    override fun secureConnectStart(call: Call) = isolate { it.secureConnectStart(call) }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        isolate { it.secureConnectEnd(call, handshake) }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) = isolate { it.connectEnd(call, inetSocketAddress, proxy, protocol) }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) = isolate { it.connectFailed(call, inetSocketAddress, proxy, protocol, ioe) }

    override fun connectionAcquired(call: Call, connection: Connection) =
        isolate { it.connectionAcquired(call, connection) }

    override fun connectionReleased(call: Call, connection: Connection) =
        isolate { it.connectionReleased(call, connection) }

    override fun requestHeadersStart(call: Call) = isolate { it.requestHeadersStart(call) }

    override fun requestHeadersEnd(call: Call, request: Request) =
        isolate { it.requestHeadersEnd(call, request) }

    override fun requestBodyStart(call: Call) = isolate { it.requestBodyStart(call) }

    override fun requestBodyEnd(call: Call, byteCount: Long) =
        isolate { it.requestBodyEnd(call, byteCount) }

    override fun requestFailed(call: Call, ioe: IOException) = isolate {
        it.requestFailed(call, ioe)
    }

    override fun responseHeadersStart(call: Call) = isolate { it.responseHeadersStart(call) }

    override fun responseHeadersEnd(call: Call, response: Response) =
        isolate { it.responseHeadersEnd(call, response) }

    override fun responseBodyStart(call: Call) = isolate { it.responseBodyStart(call) }

    override fun responseBodyEnd(call: Call, byteCount: Long) =
        isolate { it.responseBodyEnd(call, byteCount) }

    override fun responseFailed(call: Call, ioe: IOException) =
        isolate { it.responseFailed(call, ioe) }

    override fun callEnd(call: Call) = isolate { it.callEnd(call) }

    override fun callFailed(call: Call, ioe: IOException) = isolate { it.callFailed(call, ioe) }

    @SuppressSignatureCheck(since = "4.4")
    override fun canceled(call: Call) = isolate { it.canceled(call) }

    @SuppressSignatureCheck(since = "4.7")
    override fun satisfactionFailure(call: Call, response: Response) =
        isolate { it.satisfactionFailure(call, response) }

    @SuppressSignatureCheck(since = "4.7")
    override fun cacheHit(call: Call, response: Response) = isolate { it.cacheHit(call, response) }

    @SuppressSignatureCheck(since = "4.7")
    override fun cacheMiss(call: Call) = isolate { it.cacheMiss(call) }

    @SuppressSignatureCheck(since = "4.7")
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) =
        isolate { it.cacheConditionalHit(call, cachedResponse) }

    @SuppressSignatureCheck(since = "5.0")
    override fun retryDecision(call: Call, exception: IOException, retry: Boolean) =
        isolate { it.retryDecision(call, exception, retry) }

    @SuppressSignatureCheck(since = "5.0")
    override fun followUpDecision(call: Call, networkResponse: Response, nextRequest: Request?) =
        isolate { it.followUpDecision(call, networkResponse, nextRequest) }

    private inline fun isolate(event: (EventListener) -> Unit) {
        val delegate = delegate ?: return
        try {
            event(delegate)
        } catch (e: Exception) {
            stop(e)
        } catch (e: LinkageError) {
            stop(e)
        }
    }

    private fun stop(failure: Throwable) {
        delegate = null
        if (failureLogged.compareAndSet(false, true)) {
            SdkRegistry.logger.error(failure) {
                "OkHttp event listener failed. Further failures are not logged"
            }
        }
    }

    companion object {
        val failureLogged = AtomicBoolean(false)
    }
}
