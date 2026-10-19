package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import net.qrator.failoverbeacon.telemetry.model.EventTls

class TlsMapperTest {
    @Test
    fun `maps the version, the cipher and the issuer`() {
        val tls = ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA")

        assertEquals(
            EventTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA"),
            TlsMapper().toTls(tls)
        )
    }
}
