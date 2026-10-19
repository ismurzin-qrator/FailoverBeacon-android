package net.qrator.failoverbeacon.okhttp.internal.mapper

import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import okhttp3.Handshake

internal class TlsMapper {
    fun toTls(handshake: Handshake, socket: Socket): ExchangeTls = ExchangeTls(
        version = handshake.tlsVersion.javaName,
        cipher = handshake.cipherSuite.javaName,
        issuer = (serverCertificate(socket) as? X509Certificate)?.issuerX500Principal?.name
    )

    // Avoid Handshake.peerCertificates: it re-verifies trust on Android
    private fun serverCertificate(socket: Socket): Certificate? = try {
        (socket as? SSLSocket)?.session?.peerCertificates?.firstOrNull()
    } catch (_: SSLPeerUnverifiedException) {
        null
    }
}
