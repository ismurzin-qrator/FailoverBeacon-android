package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import net.qrator.failoverbeacon.telemetry.model.EventTls

internal class TlsMapper {
    fun toTls(tls: ExchangeTls): EventTls = EventTls(tls.version, tls.cipher, tls.issuer)
}
