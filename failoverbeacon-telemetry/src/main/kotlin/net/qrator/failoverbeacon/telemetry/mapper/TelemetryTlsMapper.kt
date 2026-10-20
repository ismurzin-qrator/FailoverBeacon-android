package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import net.qrator.failoverbeacon.telemetry.model.TelemetryTls

internal class TelemetryTlsMapper {
    fun toTls(tls: ExchangeTls): TelemetryTls = TelemetryTls(tls.version, tls.cipher, tls.issuer)
}
