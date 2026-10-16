package net.qrator.failoverbeacon.okhttp.internal

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.FailureType
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException

class OkHttpFailureMapperTest {
    @Test
    fun `connect failures`() {
        assertType(
            FailureType.REFUSED,
            ConnectException("Failed to connect to /127.0.0.1:1")
                .causedBy(ConnectException("Connection refused"))
        )
        assertType(
            FailureType.ADDRESS_UNREACHABLE,
            ConnectException("Failed to connect to /1.1.1.1:80")
                .causedBy(ConnectException("Network is unreachable"))
        )
        assertType(FailureType.ADDRESS_UNREACHABLE, NoRouteToHostException("No route to host"))
        assertType(FailureType.TIMED_OUT, SocketTimeoutException("connect timed out"))
        assertType(FailureType.OTHER, ConnectException("Failed to connect to /127.0.0.1:1"))
    }

    @Test
    fun `failures on an open connection`() {
        assertType(FailureType.RESET, SocketException("Connection reset"))
        assertType(
            FailureType.TIMED_OUT,
            SocketTimeoutException("timeout").causedBy(SocketException("Socket closed"))
        )
        assertType(
            FailureType.CLOSED,
            IOException("unexpected end of stream on http://127.0.0.1:1/...")
                .causedBy(EOFException("\\n not found: limit=0 content=…"))
        )
        assertType(FailureType.TIMED_OUT, InterruptedIOException("timeout"))
        assertType(FailureType.OTHER, SocketException("Socket closed"))
        assertType(FailureType.OTHER, IOException("Canceled"))
    }

    @Test
    fun `HTTP failures`() {
        assertType(FailureType.CLOSED, ProtocolException("unexpected end of stream"))
        assertType(FailureType.PROTOCOL_ERROR, ProtocolException("Unexpected status line: x"))
        assertType(
            FailureType.PROTOCOL_ERROR,
            ProtocolException("Expected leading [0-9a-fA-F] character but was 0x7a")
        )
        assertType(FailureType.STREAM_RESET, StreamResetException(ErrorCode.REFUSED_STREAM))
        assertType(FailureType.CLOSED, ConnectionShutdownException())
    }

    @Test
    fun `DNS failures`() {
        assertType(
            FailureType.NAME_NOT_RESOLVED,
            UnknownHostException("Unable to resolve host \"a.invalid\": No address associated")
        )
    }

    @Test
    fun `TLS failures`() {
        val untrusted = CertPathValidatorException("Trust anchor for certification path not found.")
        assertType(
            FailureType.CERT_UNTRUSTED,
            SSLHandshakeException(untrusted.toString())
                .causedBy(CertificateException(untrusted.toString()).causedBy(untrusted))
        )
        assertType(
            FailureType.CERT_NAME_MISMATCH,
            SSLPeerUnverifiedException("Hostname 127.0.0.1 not verified:\n    certificate: …")
        )
        assertType(
            FailureType.CERT_PIN_MISMATCH,
            SSLPeerUnverifiedException("Certificate pinning failure!\n  Peer certificate chain: …")
        )
        assertType(
            FailureType.RESET,
            SSLHandshakeException(
                "SSL handshake aborted: ssl=0x7386880fc0: I/O error during system call, " +
                    "Connection reset by peer"
            )
        )
        assertType(FailureType.CLOSED, SSLHandshakeException("Connection closed by peer"))
        assertType(FailureType.TIMED_OUT, SocketTimeoutException("SSL handshake timed out"))
        assertType(
            FailureType.TLS_PROTOCOL_ERROR,
            SSLHandshakeException("Handshake failed").causedBy(
                SSLProtocolException("SSL handshake aborted: ssl=0x1: Failure in SSL library")
            )
        )
        assertType(FailureType.OTHER, SSLException("Unable to parse TLS packet header"))
    }

    @Test
    fun `a class with one meaning wins over a message`() {
        assertType(
            FailureType.TLS_PROTOCOL_ERROR,
            SSLHandshakeException("aborted, Connection reset by peer")
                .causedBy(SSLProtocolException("Failure in SSL library"))
        )
    }

    @Test
    fun `a message wins over a class with several meanings`() {
        assertType(
            FailureType.CERT_PIN_MISMATCH,
            SSLPeerUnverifiedException("Certificate pinning failure!")
        )
    }

    @Test
    fun `a message matches only its exception class`() {
        assertType(FailureType.OTHER, IOException("Connection reset"))
        assertType(FailureType.OTHER, SSLException("Connection closed by peer"))
    }

    @Test
    fun `a cyclic cause chain ends`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertType(FailureType.OTHER, first)
    }

    private fun assertType(expected: FailureType, failure: Throwable) {
        assertEquals(expected, OkHttpFailureMapper().toFailureType(failure), failure.toString())
    }

    private fun <T : Throwable> T.causedBy(cause: Throwable): T = apply { initCause(cause) }
}
