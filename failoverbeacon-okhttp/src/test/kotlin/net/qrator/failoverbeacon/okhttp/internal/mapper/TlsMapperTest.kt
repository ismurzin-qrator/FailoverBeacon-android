package net.qrator.failoverbeacon.okhttp.internal.mapper

import java.security.cert.Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.TlsVersion
import okhttp3.tls.HeldCertificate

class TlsMapperTest {
    private val mapper = TlsMapper()

    @Test
    fun `maps the version, the cipher and the issuer of the server certificate`() {
        val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test CA").build()
        val server = HeldCertificate.Builder().commonName("a.test").signedBy(root).build()
        val handshake = handshake(listOf(server.certificate, root.certificate))

        assertEquals(
            ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA"),
            mapper.toTls(handshake)
        )
    }

    @Test
    fun `a handshake without certificates has no issuer`() {
        assertEquals(
            ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", issuer = null),
            mapper.toTls(handshake(emptyList()))
        )
    }

    /** Kotlin calls the factory through the companion object, which OkHttp 3 has not. */
    private fun handshake(certificates: List<Certificate>): Handshake {
        val factory = Handshake::class.java.getMethod(
            "get",
            TlsVersion::class.java,
            CipherSuite::class.java,
            List::class.java,
            List::class.java
        )
        val tlsVersion = TlsVersion.TLS_1_3
        val cipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256
        return factory.invoke(null, tlsVersion, cipherSuite, certificates, emptyList<Any>())
            as Handshake
    }
}
