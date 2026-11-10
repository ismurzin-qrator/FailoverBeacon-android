package net.qrator.failoverbeacon.failover.probe

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.model.BodyProbeConfig
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome

internal class BodyProbe(
    private val clock: MonotonicClock,
    private val socketFactory: () -> SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier,
    private val timeoutMillis: Int,
    private val deadlineMillis: Long
) : Closeable {
    private val sockets = OpenSockets()

    fun run(
        address: InetAddress,
        host: String,
        port: Int,
        settings: BodyProbeConfig
    ): BodyProbeOutcome {
        val maxBytes = settings.maxBytes
        val socket = sockets.open() ?: return BodyProbeOutcome.TCP_FAILED
        try {
            val start = clock.nanoTime()
            val target = InetSocketAddress(address, port)
            if (!connect(socket, target, maxBytes / 2)) return BodyProbeOutcome.TCP_FAILED
            val tls = handshake(socket, host, port) ?: return BodyProbeOutcome.TLS_FAILED
            return tls.use {
                val isFullResponse = try {
                    tls.outputStream.write(request(host, port, settings.path, maxBytes))
                    tls.outputStream.flush()
                    readResponse(tls.inputStream, maxBytes, start, host, address)
                } catch (_: IOException) {
                    false
                }
                if (isFullResponse) BodyProbeOutcome.PASSED else BodyProbeOutcome.BODY_FAILED
            }
        } finally {
            sockets.release(socket)
        }
    }

    fun abort() = sockets.abortAll()

    override fun close() = sockets.close()

    private fun connect(socket: Socket, target: InetSocketAddress, window: Int): Boolean = try {
        socket.receiveBufferSize = window
        socket.connect(target, timeoutMillis)
        socket.soTimeout = timeoutMillis
        true
    } catch (_: IOException) {
        false
    }

    private fun handshake(socket: Socket, host: String, port: Int): SSLSocket? {
        val tls = try {
            socketFactory().createSocket(socket, host, port, true) as SSLSocket
        } catch (_: IOException) {
            return null
        }
        val isVerified = try {
            tls.startHandshake()
            // SSLSocket does not compare the certificate with the host name.
            hostnameVerifier.verify(host, tls.session)
        } catch (_: IOException) {
            false
        }
        if (!isVerified) runCatching(tls::close)
        return tls.takeIf { isVerified }
    }

    private fun request(host: String, port: Int, path: String, maxBytes: Int): ByteArray {
        val authority = if (port == HTTPS_PORT) host else "$host:$port"
        return (
            "GET $path HTTP/1.1\r\n" +
                "Host: $authority\r\n" +
                "Range: bytes=0-${maxBytes - 1}\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n" +
                "User-Agent: failoverbeacon\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    private fun readResponse(
        input: InputStream,
        maxBytes: Int,
        startNanos: Long,
        host: String,
        address: InetAddress
    ): Boolean {
        val deadlineNanos = TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
        val buffer = ByteArray(BUFFER_BYTES)
        val head = StringBuilder()
        var isHeadRead = false
        var expectedBytes: Long? = null
        var total = 0
        var isEnded = false
        while (total < maxBytes && !isEnded && total < (expectedBytes ?: Long.MAX_VALUE)) {
            if (clock.nanoTime() - startNanos > deadlineNanos) return false
            val count = input.read(buffer, 0, min(buffer.size, maxBytes - total))
            if (count < 0) {
                isEnded = true
                continue
            }
            total += count
            if (!isHeadRead) {
                head.append(String(buffer, 0, count, Charsets.ISO_8859_1))
                val headEnd = head.indexOf(HEAD_END)
                if (headEnd >= 0) {
                    isHeadRead = true
                    expectedBytes = contentLength(head.substring(0, headEnd))
                        ?.plus(headEnd + HEAD_END.length)
                }
            }
        }
        SdkRegistry.logger.debug {
            "[Failover] $host ${address.hostAddress}: ${head.lineSequence().first().trim()}, $total bytes"
        }
        // The end of the stream looks the same with and without close_notify.
        val expected = expectedBytes
        return !isEnded || (isHeadRead && (expected == null || total >= expected))
    }

    private fun contentLength(head: String): Long? =
        CONTENT_LENGTH.find(head)?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        const val HTTPS_PORT = 443
        const val BUFFER_BYTES = 8192
        const val HEAD_END = "\r\n\r\n"
        val CONTENT_LENGTH = Regex(
            """^content-length:\s*(\d+)\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
    }
}
