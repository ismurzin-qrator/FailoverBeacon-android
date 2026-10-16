package net.qrator.failoverbeacon.core.call.mapper

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import net.qrator.failoverbeacon.core.call.model.FailureType

internal val TestFailureMapper = FailureMapper { failure ->
    when {
        failure is UnknownHostException -> FailureType.NAME_NOT_RESOLVED
        failure is ConnectException -> FailureType.REFUSED
        failure is InterruptedIOException -> FailureType.TIMED_OUT
        failure is SSLHandshakeException -> FailureType.CERT_UNTRUSTED
        failure is ProtocolException -> FailureType.PROTOCOL_ERROR
        failure is SocketException && failure.message == "Socket closed" -> FailureType.CLOSED
        failure is SocketException -> FailureType.RESET
        else -> FailureType.OTHER
    }
}
