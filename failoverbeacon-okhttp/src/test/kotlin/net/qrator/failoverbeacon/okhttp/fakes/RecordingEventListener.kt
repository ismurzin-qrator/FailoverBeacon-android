package net.qrator.failoverbeacon.okhttp.fakes

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class RecordingEventListener(
    val log: MutableList<String> = CopyOnWriteArrayList(),
    private val prefix: String = "",
    private val failure: Throwable? = null
) : EventListener() {
    private fun record(event: String) {
        log += prefix + event
        if (failure != null) throw failure
    }

    override fun callStart(call: Call) = record("callStart")

    override fun dispatcherQueueStart(call: Call, dispatcher: Dispatcher) =
        record("dispatcherQueueStart")

    override fun dispatcherQueueEnd(call: Call, dispatcher: Dispatcher) =
        record("dispatcherQueueEnd")

    override fun proxySelectStart(call: Call, url: HttpUrl) = record("proxySelectStart")

    override fun proxySelectEnd(
        call: Call,
        url: HttpUrl,
        proxies: List<@JvmSuppressWildcards Proxy>
    ) = record("proxySelectEnd")

    override fun dnsStart(call: Call, domainName: String) = record("dnsStart")

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<@JvmSuppressWildcards InetAddress>
    ) = record("dnsEnd")

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        record("connectStart")

    override fun secureConnectStart(call: Call) = record("secureConnectStart")

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = record("secureConnectEnd")

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) = record("connectEnd")

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) = record("connectFailed")

    override fun connectionAcquired(call: Call, connection: Connection) =
        record("connectionAcquired")

    override fun connectionReleased(call: Call, connection: Connection) =
        record("connectionReleased")

    override fun requestHeadersStart(call: Call) = record("requestHeadersStart")

    override fun requestHeadersEnd(call: Call, request: Request) = record("requestHeadersEnd")

    override fun requestBodyStart(call: Call) = record("requestBodyStart")

    override fun requestBodyEnd(call: Call, byteCount: Long) = record("requestBodyEnd")

    override fun requestFailed(call: Call, ioe: IOException) = record("requestFailed")

    override fun responseHeadersStart(call: Call) = record("responseHeadersStart")

    override fun responseHeadersEnd(call: Call, response: Response) = record("responseHeadersEnd")

    override fun responseBodyStart(call: Call) = record("responseBodyStart")

    override fun responseBodyEnd(call: Call, byteCount: Long) = record("responseBodyEnd")

    override fun responseFailed(call: Call, ioe: IOException) = record("responseFailed")

    override fun callEnd(call: Call) = record("callEnd")

    override fun callFailed(call: Call, ioe: IOException) = record("callFailed")

    override fun canceled(call: Call) = record("canceled")

    override fun satisfactionFailure(call: Call, response: Response) = record("satisfactionFailure")

    override fun cacheHit(call: Call, response: Response) = record("cacheHit")

    override fun cacheMiss(call: Call) = record("cacheMiss")

    override fun cacheConditionalHit(call: Call, cachedResponse: Response) =
        record("cacheConditionalHit")

    override fun retryDecision(call: Call, exception: IOException, retry: Boolean) =
        record("retryDecision")

    override fun followUpDecision(call: Call, networkResponse: Response, nextRequest: Request?) =
        record("followUpDecision")
}
