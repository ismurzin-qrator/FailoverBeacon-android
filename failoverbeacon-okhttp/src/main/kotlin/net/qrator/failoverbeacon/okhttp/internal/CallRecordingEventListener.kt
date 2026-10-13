package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.CallRecorder
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Records one call into [recorder] */
internal class CallRecordingEventListener(private val recorder: CallRecorder) : EventListener() {
    @Volatile
    private var failed = false

    override fun callStart(call: Call) = safely {
        val request = call.request()
        recorder.callStart(request.url.host, request.url.port, request.method)
    }

    override fun dnsStart(call: Call, domainName: String) = safely { recorder.dnsStart() }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<@JvmSuppressWildcards InetAddress>
    ) = safely { recorder.dnsEnd() }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        safely { recorder.connectStart(inetSocketAddress.ip(), inetSocketAddress.port) }

    override fun secureConnectStart(call: Call) = safely { recorder.tlsStart() }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = safely { recorder.tlsEnd() }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) = safely { recorder.connectEnd(inetSocketAddress.ip(), inetSocketAddress.port) }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) = safely { recorder.connectFailed(inetSocketAddress.ip(), inetSocketAddress.port, ioe) }

    override fun connectionAcquired(call: Call, connection: Connection) = safely {
        val route = connection.route()
        recorder.connectionAcquired(
            route.socketAddress.ip(),
            route.socketAddress.port,
            route.address.url.host,
            route.address.url.port,
            connection.protocol().toString()
        )
    }

    override fun requestHeadersStart(call: Call) = safely { recorder.requestHeadersStart() }

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
            response.protocol.toString(),
            response.headers["Content-Length"]?.toLongOrNull()
        )
    }

    override fun responseBodyStart(call: Call) = safely { recorder.responseBodyStart() }

    override fun responseBodyEnd(call: Call, byteCount: Long) =
        safely { recorder.responseBodyEnd(byteCount) }

    override fun responseFailed(call: Call, ioe: IOException) =
        safely { recorder.responseFailed(ioe) }

    override fun callEnd(call: Call) = safely { recorder.callEnd() }

    override fun callFailed(call: Call, ioe: IOException) = safely {
        // A call timeout cancels the call too, so it's a failure.
        val timedOut = ioe is InterruptedIOException && call.timeout().timeoutNanos() > 0
        recorder.callFailed(ioe, canceled = call.isCanceled() && !timedOut, timedOut = timedOut)
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

    private fun InetSocketAddress.ip(): String = address?.hostAddress ?: hostString

    companion object {
        /** Whether the SDK fits this OkHttp version. */
        @Volatile
        var incompatible = false
    }
}
