package net.qrator.failoverbeacon.telemetry.serialization

import android.util.JsonWriter
import java.io.StringWriter
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttempt
import net.qrator.failoverbeacon.telemetry.model.TelemetryCarrier
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent
import net.qrator.failoverbeacon.telemetry.model.TelemetryNetwork
import net.qrator.failoverbeacon.telemetry.model.TelemetryTimings
import net.qrator.failoverbeacon.telemetry.model.TelemetryTls

internal class TelemetryEventSerializer {
    fun toJson(event: TelemetryEvent, samplingFraction: Double): String {
        val out = StringWriter()
        JsonWriter(out).use { it.writeEvent(event, samplingFraction) }
        return out.toString()
    }

    private fun JsonWriter.writeEvent(event: TelemetryEvent, samplingFraction: Double) {
        beginObject()
        name("format_version").value(FORMAT_VERSION)
        name("call_id").value(event.callId.toULong().toString(HEX).padStart(CALL_ID_LENGTH, '0'))
        name("resend_count").value(event.resendCount)
        name("start_time").value(event.startTimeMillis)
        name("elapsed_time").value(event.elapsedMs)
        name("sampling_fraction").value(samplingFraction)
        optional("scheme", event.scheme)
        optional("host", event.host)
        optional("port", event.port)
        optional("method", event.method)
        name("phase").value(event.phase.code)
        name("type").value(event.type.code)
        optional("step", event.step?.code)
        optional("status_code", event.statusCode)
        optional("protocol", event.protocol)
        optional("server_ip", event.serverIp)
        optional("proxy", event.proxy?.code)
        event.tls?.let { writeTls(it) }
        name("reused_connection").value(event.reusedConnection)
        event.dnsAddresses?.let { writeStrings("dns_addresses", it) }
        writeTimings(event.timings)
        optional("request_body_bytes", event.requestBodyBytes)
        optional("response_content_length", event.responseContentLength)
        optional("response_body_bytes", event.responseBodyBytes)
        if (event.attempts.isNotEmpty()) writeAttempts(event.attempts)
        writeNetwork(event.network)
        optional("foreground", event.foreground)
        endObject()
    }

    private fun JsonWriter.writeTls(tls: TelemetryTls) {
        name("tls").beginObject()
        name("version").value(tls.version)
        name("cipher").value(tls.cipher)
        optional("issuer", tls.issuer)
        endObject()
    }

    private fun JsonWriter.writeStrings(name: String, values: List<String>) {
        name(name).beginArray()
        values.forEach { value(it) }
        endArray()
    }

    private fun JsonWriter.writeTimings(timings: TelemetryTimings) {
        val phases = with(timings) {
            listOf(
                "dns" to dnsMs,
                "connect" to connectMs,
                "tls" to tlsMs,
                "send" to sendMs,
                "wait" to waitMs,
                "receive" to receiveMs
            )
        }
        if (phases.all { it.second == null }) return
        name("timings").beginObject()
        phases.forEach { (phase, ms) -> optional(phase, ms) }
        endObject()
    }

    private fun JsonWriter.writeAttempts(attempts: List<TelemetryAttempt>) {
        name("attempts").beginArray()
        for ((ip, proxy, ms, result, type, step) in attempts) {
            beginObject()
            name("ip").value(ip)
            name("proxy").value(proxy.code)
            optional("elapsed_time", ms)
            name("result").value(result.code)
            optional("type", type?.code)
            optional("step", step?.code)
            endObject()
        }
        endArray()
    }

    private fun JsonWriter.writeNetwork(network: TelemetryNetwork) {
        name("network").beginObject()
        when (network) {
            TelemetryNetwork.Unknown -> name("state").value("unknown")
            TelemetryNetwork.None -> name("state").value("none")
            is TelemetryNetwork.Connected -> writeConnected(network)
        }
        endObject()
    }

    private fun JsonWriter.writeConnected(network: TelemetryNetwork.Connected) {
        name("state").value("connected")
        name("transport").value(network.transport.code)
        name("vpn").value(network.vpn)
        name("validated").value(network.validated)
        name("captive_portal").value(network.captivePortal)
        name("metered").value(network.metered)
        optional("roaming", network.roaming)
        optional("downstream_kbps", network.downstreamKbps)
        optional("upstream_kbps", network.upstreamKbps)
        optional("signal_strength", network.signalStrength)
        optional("blocked", network.blocked)
        network.carrier?.let { writeCarrier(it) }
    }

    private fun JsonWriter.writeCarrier(carrier: TelemetryCarrier) {
        name("carrier").beginObject()
        optional("name", carrier.name)
        optional("mcc", carrier.mcc)
        optional("mnc", carrier.mnc)
        optional("carrier_id", carrier.carrierId)
        optional("network_type", carrier.networkType)
        endObject()
    }

    private fun JsonWriter.optional(name: String, value: String?) {
        if (value != null) name(name).value(value)
    }

    private fun JsonWriter.optional(name: String, value: Number?) {
        if (value != null) name(name).value(value)
    }

    private fun JsonWriter.optional(name: String, value: Boolean?) {
        if (value != null) name(name).value(value)
    }

    private companion object {
        const val FORMAT_VERSION = 1L
        const val HEX = 16
        const val CALL_ID_LENGTH = 16
    }
}
