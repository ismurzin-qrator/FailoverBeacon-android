package net.qrator.failoverbeacon.okhttp.internal.mapper

import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.TlsVersion

/** The issuer of a real handshake is checked in CallRecordingTest. */
class OkHttpTlsMapperTest {
    private val mapper = OkHttpTlsMapper()

    @Test
    fun `maps the version and the cipher, a socket without TLS has no issuer`() {
        Socket().use { socket ->
            assertEquals(
                ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", issuer = null),
                mapper.toTls(handshake(), socket)
            )
        }
    }

    @Test
    fun `a TLS socket without a session has no issuer`() {
        SSLSocketFactory.getDefault().createSocket().use { socket ->
            assertEquals(null, mapper.toTls(handshake(), socket).issuer)
        }
    }

    /** Kotlin calls the factory through the companion object, which OkHttp 3 has not. */
    private fun handshake(): Handshake {
        val factory = Handshake::class.java.getMethod(
            "get",
            TlsVersion::class.java,
            CipherSuite::class.java,
            List::class.java,
            List::class.java
        )
        val tlsVersion = TlsVersion.TLS_1_3
        val cipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256
        return factory.invoke(null, tlsVersion, cipherSuite, emptyList<Any>(), emptyList<Any>())
            as Handshake
    }
}
