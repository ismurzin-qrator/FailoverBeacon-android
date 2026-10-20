package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketException
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.CallRecorder
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.state.CallStopReason
import net.qrator.failoverbeacon.core.call.state.ConnectStopReason
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure
import net.qrator.failoverbeacon.okhttp.internal.mapper.OkHttpProxyTypeMapper
import net.qrator.failoverbeacon.okhttp.internal.mapper.OkHttpTlsMapper
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Records one call into [recorder]. */
internal class CallRecordingEventListener(private val recorder: CallRecorder) : EventListener() {
    private val proxyTypeMapper = OkHttpProxyTypeMapper()
    private val tlsMapper = OkHttpTlsMapper()

    @Volatile
    private var failed = false

    // OkHttp 5 connects TCP on its own threads but runs the handshake on the call's thread, so only
    // a failure reported on the handshake's thread is the handshake's.
    @Volatile
    private var handshakeThread: Thread? = null

    // OkHttp reports no connectionAcquired for a follow-up on the connection the call keeps, and
    // keeps it only for the same host and port:
    // Transmitter.java:126 in 3.14.9,
    // ExchangeFinder.kt:157 in 4.12.0,
    // RealRoutePlanner.planReuseCallConnection in 5.x.
    // Every request is announced on it again.
    @Volatile
    private var heldConnection: Connection? = null

    override fun callStart(call: Call) = safely {
        val request = call.request()
        recorder.callStart(request.url.host, request.url.port, request.method)
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) =
        safely { recorder.targetSelected(url.host, url.port) }

    override fun dnsStart(call: Call, domainName: String) = safely { recorder.dnsStart() }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<@JvmSuppressWildcards InetAddress>
    ) = safely { recorder.dnsEnd(inetAddressList.mapNotNull { it.hostAddress }) }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        safely {
            val proxyType = proxyTypeMapper.toProxyType(proxy)
            recorder.connectStart(
                inetSocketAddress.peerAddress(),
                inetSocketAddress.port,
                proxyType
            )
        }

    override fun secureConnectStart(call: Call) = safely {
        handshakeThread = Thread.currentThread()
        recorder.tlsStart()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = safely { recorder.tlsEnd() }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) = safely {
        endHandshake()
        recorder.connectEnd(inetSocketAddress.peerAddress(), inetSocketAddress.port)
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) = safely {
        val step = if (endHandshake()) ExchangeStep.TLS else ExchangeStep.TCP
        val stop = when {
            // A call, also a timed-out one, is marked canceled before its sockets are closed.
            call.isCanceled() -> ConnectStopReason.CALL

            // OkHttp drops a connection it no longer needs by closing its socket, or before creating it.
            step == ExchangeStep.TCP && (
                ioe.javaClass == SocketException::class.java ||
                    ioe.javaClass == IOException::class.java
                ) -> ConnectStopReason.CLIENT

            else -> ConnectStopReason.NETWORK
        }
        recorder.connectFailed(
            inetSocketAddress.peerAddress(),
            inetSocketAddress.port,
            ioe,
            step,
            stop
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) =
        safely { acquire(connection) }

    override fun requestHeadersStart(call: Call) = safely {
        heldConnection?.let(::acquire)
        recorder.requestHeadersStart()
    }

    override fun requestHeadersEnd(call: Call, request: Request) = safely {
        recorder.requestHeadersEnd(request.url.host, request.url.port, request.method)
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) =
        safely { recorder.requestBodyEnd(byteCount) }

    override fun requestFailed(call: Call, ioe: IOException) = safely {
        recorder.requestFailed(ioe)
    }

    override fun responseHeadersEnd(call: Call, response: Response) = safely {
        recorder.responseHeadersEnd(
            response.code,
            response.headers["Content-Length"]?.toLongOrNull()
        )
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) =
        safely { recorder.responseBodyEnd(byteCount) }

    override fun responseFailed(call: Call, ioe: IOException) =
        safely { recorder.responseFailed(ioe) }

    override fun callEnd(call: Call) = safely { recorder.callEnd() }

    override fun callFailed(call: Call, ioe: IOException) = safely {
        val stop = when {
            // A call timeout fails with exactly this class.
            ioe.javaClass == InterruptedIOException::class.java &&
                call.timeout().timeoutNanos() > 0 -> CallStopReason.TIMED_OUT

            call.isCanceled() -> CallStopReason.CANCELED

            // OkHttp rejects a response it cannot follow, as after too many redirects.
            ioe is ProtocolException -> CallStopReason.RESPONSE_REJECTED

            else -> CallStopReason.FAILED
        }
        recorder.callFailed(ioe, stop)
    }

    private fun acquire(connection: Connection) {
        heldConnection = connection
        val route = connection.route()
        recorder.connectionAcquired(
            peerAddress = route.socketAddress.peerAddress(),
            peerPort = route.socketAddress.port,
            serverAddress = route.address.url.host,
            serverPort = route.address.url.port,
            protocol = connection.protocol().toString(),
            proxy = proxyTypeMapper.toProxyType(route.proxy),
            tls = connection.handshake()?.let { tlsMapper.toTls(it, connection.socket()) }
        )
    }

    /** Whether this thread was in a handshake, which has now ended. */
    private fun endHandshake(): Boolean {
        if (handshakeThread !== Thread.currentThread()) return false
        handshakeThread = null
        return true
    }

    private inline fun safely(block: () -> Unit) {
        if (!failed) catchSdkFailure(::stop, block)
    }

    private fun stop(failure: Throwable) {
        failed = true
        if (failure is LinkageError) {
            incompatible = true
            SdkRegistry.logger.error(failure, onlyOnce = true) {
                "OkHttp version is incompatible with the SDK. New calls are not observed"
            }
        } else {
            SdkRegistry.logger.error(failure, onlyOnce = true) {
                "Recording an OkHttp call failed. Further failures are not logged"
            }
        }
    }

    private fun InetSocketAddress.peerAddress(): String = address?.hostAddress ?: hostString

    companion object {
        /** Whether the SDK does not fit this OkHttp version. */
        @Volatile
        var incompatible = false
    }
}
