package net.qrator.failoverbeacon.okhttp.internal.mapper

import java.security.cert.X509Certificate
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import okhttp3.Handshake

internal class TlsMapper {
    fun toTls(handshake: Handshake): ExchangeTls = ExchangeTls(
        version = handshake.tlsVersion.javaName,
        cipher = handshake.cipherSuite.javaName,
        issuer = (handshake.peerCertificates.firstOrNull() as? X509Certificate)
            ?.issuerX500Principal
            ?.name
    )
}
