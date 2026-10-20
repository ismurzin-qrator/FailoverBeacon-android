package net.qrator.failoverbeacon.okhttp.internal.mapper

import android.system.ErrnoException
import android.system.OsConstants.EADDRNOTAVAIL
import android.system.OsConstants.EAFNOSUPPORT
import android.system.OsConstants.ECONNABORTED
import android.system.OsConstants.ECONNREFUSED
import android.system.OsConstants.ECONNRESET
import android.system.OsConstants.EHOSTUNREACH
import android.system.OsConstants.ENETRESET
import android.system.OsConstants.ENETUNREACH
import android.system.OsConstants.EPIPE
import android.system.OsConstants.ETIMEDOUT
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import net.qrator.failoverbeacon.core.call.mapper.FailureMapper
import net.qrator.failoverbeacon.core.call.model.FailureType
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.StreamResetException

internal class OkHttpFailureMapper : FailureMapper {
    override fun toFailureType(failure: Throwable): FailureType {
        val chain = generateSequence(failure) { it.cause }.take(MAX_CAUSES).toList()
        return chain.firstNotNullOfOrNull(::byErrno)
            ?: chain.firstNotNullOfOrNull(::byClass)
            ?: chain.firstNotNullOfOrNull(::byMessage)
            ?: chain.firstNotNullOfOrNull(::byGeneralClass)
            ?: FailureType.OTHER
    }

    private fun byErrno(failure: Throwable): FailureType? {
        if (failure !is ErrnoException) return null
        return when (failure.errno) {
            ETIMEDOUT -> FailureType.TIMED_OUT
            ECONNRESET, ENETRESET, EPIPE -> FailureType.RESET
            ECONNABORTED -> FailureType.ABORTED
            ECONNREFUSED -> FailureType.REFUSED
            EHOSTUNREACH, ENETUNREACH, EAFNOSUPPORT -> FailureType.ADDRESS_UNREACHABLE
            EADDRNOTAVAIL -> FailureType.ADDRESS_INVALID
            else -> null
        }
    }

    private fun byClass(failure: Throwable): FailureType? = when (failure) {
        is InterruptedIOException -> FailureType.TIMED_OUT
        is UnknownHostException -> FailureType.NAME_NOT_RESOLVED
        is NoRouteToHostException -> FailureType.ADDRESS_UNREACHABLE
        is EOFException -> FailureType.CLOSED
        is CertificateException -> FailureType.CERT_UNTRUSTED
        is SSLProtocolException -> FailureType.TLS_PROTOCOL_ERROR
        is StreamResetException -> FailureType.STREAM_RESET
        is ConnectionShutdownException -> FailureType.CLOSED
        else -> null
    }

    private fun byMessage(failure: Throwable): FailureType? {
        val message = failure.message ?: return null
        return when {
            failure is SocketException && message == CONNECTION_RESET -> FailureType.RESET

            failure is ConnectException && message == CONNECTION_REFUSED -> FailureType.REFUSED

            failure is ConnectException && message == NETWORK_UNREACHABLE ->
                FailureType.ADDRESS_UNREACHABLE

            failure is SSLException && message.endsWith(RESET_BY_PEER_SUFFIX) ->
                FailureType.RESET

            failure is SSLHandshakeException && message == CLOSED_BY_PEER ->
                FailureType.CLOSED

            failure is SSLPeerUnverifiedException &&
                message.startsWith(PINNING_FAILURE_PREFIX) ->
                FailureType.CERT_PIN_MISMATCH

            failure is ProtocolException && message == UNEXPECTED_END_OF_STREAM ->
                FailureType.CLOSED

            failure is ProtocolException && message.startsWith(TOO_MANY_FOLLOW_UPS_PREFIX) ->
                FailureType.REDIRECT_LOOP

            else -> null
        }
    }

    private fun byGeneralClass(failure: Throwable): FailureType? = when (failure) {
        is SSLPeerUnverifiedException -> FailureType.CERT_NAME_MISMATCH
        is ProtocolException -> FailureType.PROTOCOL_ERROR
        else -> null
    }

    private companion object {
        const val MAX_CAUSES = 8

        // Messages without errno on Android: every API level.
        const val CONNECTION_RESET = "Connection reset"
        const val CONNECTION_REFUSED = "Connection refused"
        const val NETWORK_UNREACHABLE = "Network is unreachable"

        // Conscrypt: strerror suffix ("%s, %s", jniutil.cc) - a FIN in the handshake on Android 24.
        const val RESET_BY_PEER_SUFFIX = ", Connection reset by peer"
        const val CLOSED_BY_PEER = "Connection closed by peer"

        // OkHttp: CertificatePinner, Http1ExchangeCodec, RetryAndFollowUpInterceptor.
        const val PINNING_FAILURE_PREFIX = "Certificate pinning failure!"
        const val UNEXPECTED_END_OF_STREAM = "unexpected end of stream"
        const val TOO_MANY_FOLLOW_UPS_PREFIX = "Too many follow-up requests"
    }
}
