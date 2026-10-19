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
        else -> byOkHttpClass(failure)
    }

    private fun byOkHttpClass(failure: Throwable): FailureType? = try {
        when (failure) {
            is StreamResetException -> FailureType.STREAM_RESET
            is ConnectionShutdownException -> FailureType.CLOSED
            else -> null
        }
    } catch (_: LinkageError) {
        // OkHttp internal classes may vary across versions.
        null
    }

    private fun byMessage(failure: Throwable): FailureType? {
        val message = failure.message ?: return null
        return when {
            failure is SocketException && message == "Connection reset" -> FailureType.RESET

            failure is ConnectException && message == "Connection refused" -> FailureType.REFUSED

            failure is ConnectException && message == "Network is unreachable" ->
                FailureType.ADDRESS_UNREACHABLE

            failure is SSLException && message.endsWith(", Connection reset by peer") ->
                FailureType.RESET

            failure is SSLHandshakeException && message == "Connection closed by peer" ->
                FailureType.CLOSED

            failure is SSLPeerUnverifiedException &&
                message.startsWith("Certificate pinning failure!") ->
                FailureType.CERT_PIN_MISMATCH

            failure is ProtocolException && message == "unexpected end of stream" ->
                FailureType.CLOSED

            failure is ProtocolException && message.startsWith("Too many follow-up requests") ->
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
    }
}
