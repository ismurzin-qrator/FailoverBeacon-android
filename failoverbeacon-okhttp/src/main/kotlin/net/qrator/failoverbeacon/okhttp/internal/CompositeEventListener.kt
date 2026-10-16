package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Sends each event to [first], then to [second], like `EventListener.plus` of OkHttp 5.3+. */
internal class CompositeEventListener(
    private val first: EventListener,
    private val second: EventListener
) : EventListener() {
    override fun callStart(call: Call) = forward { it.callStart(call) }

    @SuppressSignatureCheck(since = "5.2")
    override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) =
        forward { it.dispatcherQueueStart(call, dispatcher) }

    @SuppressSignatureCheck(since = "5.2")
    override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) =
        forward { it.dispatcherQueueEnd(call, dispatcher) }

    @SuppressSignatureCheck(since = "4.1")
    override fun proxySelectStart(call: Call, url: HttpUrl) =
        forward { it.proxySelectStart(call, url) }

    @SuppressSignatureCheck(since = "4.1")
    override fun proxySelectEnd(
        call: Call,
        url: HttpUrl,
        proxies: List<@JvmSuppressWildcards Proxy>
    ) = forward { it.proxySelectEnd(call, url, proxies) }

    override fun dnsStart(call: Call, domainName: String) =
        forward { it.dnsStart(call, domainName) }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<@JvmSuppressWildcards InetAddress>
    ) = forward { it.dnsEnd(call, domainName, inetAddressList) }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        forward { it.connectStart(call, inetSocketAddress, proxy) }

    override fun secureConnectStart(call: Call) = forward { it.secureConnectStart(call) }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        forward { it.secureConnectEnd(call, handshake) }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) = forward { it.connectEnd(call, inetSocketAddress, proxy, protocol) }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) = forward { it.connectFailed(call, inetSocketAddress, proxy, protocol, ioe) }

    override fun connectionAcquired(call: Call, connection: Connection) =
        forward { it.connectionAcquired(call, connection) }

    override fun connectionReleased(call: Call, connection: Connection) =
        forward { it.connectionReleased(call, connection) }

    override fun requestHeadersStart(call: Call) = forward { it.requestHeadersStart(call) }

    override fun requestHeadersEnd(call: Call, request: Request) =
        forward { it.requestHeadersEnd(call, request) }

    override fun requestBodyStart(call: Call) = forward { it.requestBodyStart(call) }

    override fun requestBodyEnd(call: Call, byteCount: Long) =
        forward { it.requestBodyEnd(call, byteCount) }

    override fun requestFailed(call: Call, ioe: IOException) =
        forward { it.requestFailed(call, ioe) }

    override fun responseHeadersStart(call: Call) = forward { it.responseHeadersStart(call) }

    override fun responseHeadersEnd(call: Call, response: Response) =
        forward { it.responseHeadersEnd(call, response) }

    override fun responseBodyStart(call: Call) = forward { it.responseBodyStart(call) }

    override fun responseBodyEnd(call: Call, byteCount: Long) =
        forward { it.responseBodyEnd(call, byteCount) }

    override fun responseFailed(call: Call, ioe: IOException) =
        forward { it.responseFailed(call, ioe) }

    override fun callEnd(call: Call) = forward { it.callEnd(call) }

    override fun callFailed(call: Call, ioe: IOException) = forward { it.callFailed(call, ioe) }

    @SuppressSignatureCheck(since = "4.4")
    override fun canceled(call: Call) = forward { it.canceled(call) }

    @SuppressSignatureCheck(since = "4.7")
    override fun satisfactionFailure(call: Call, response: Response) =
        forward { it.satisfactionFailure(call, response) }

    @SuppressSignatureCheck(since = "4.7")
    override fun cacheHit(call: Call, response: Response) = forward { it.cacheHit(call, response) }

    @SuppressSignatureCheck(since = "4.7")
    override fun cacheMiss(call: Call) = forward { it.cacheMiss(call) }

    @SuppressSignatureCheck(since = "4.7")
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) =
        forward { it.cacheConditionalHit(call, cachedResponse) }

    @SuppressSignatureCheck(since = "5.0")
    override fun retryDecision(call: Call, exception: IOException, retry: Boolean) =
        forward { it.retryDecision(call, exception, retry) }

    @SuppressSignatureCheck(since = "5.0")
    override fun followUpDecision(call: Call, networkResponse: Response, nextRequest: Request?) =
        forward { it.followUpDecision(call, networkResponse, nextRequest) }

    private inline fun forward(event: (EventListener) -> Unit) {
        event(first)
        event(second)
    }
}
