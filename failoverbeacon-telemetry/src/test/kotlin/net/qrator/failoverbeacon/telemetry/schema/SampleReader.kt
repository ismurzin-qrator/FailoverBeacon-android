package net.qrator.failoverbeacon.telemetry.schema

import net.qrator.failoverbeacon.telemetry.DropReason
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttempt
import net.qrator.failoverbeacon.telemetry.model.TelemetryAttemptResult
import net.qrator.failoverbeacon.telemetry.model.TelemetryCarrier
import net.qrator.failoverbeacon.telemetry.model.TelemetryErrorType
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent
import net.qrator.failoverbeacon.telemetry.model.TelemetryNetwork
import net.qrator.failoverbeacon.telemetry.model.TelemetryNetworkTransport
import net.qrator.failoverbeacon.telemetry.model.TelemetryPhase
import net.qrator.failoverbeacon.telemetry.model.TelemetryProxyType
import net.qrator.failoverbeacon.telemetry.model.TelemetryStep
import net.qrator.failoverbeacon.telemetry.model.TelemetryTimings
import net.qrator.failoverbeacon.telemetry.model.TelemetryTls
import net.qrator.failoverbeacon.telemetry.model.UploadMetadata
import org.json.JSONObject

/** Builds the SDK's models from schema samples, so a test can serialize them back. */
internal object SampleReader {
    fun event(json: JSONObject) = TelemetryEvent(
        callId = java.lang.Long.parseUnsignedLong(json.getString("call_id"), 16),
        resendCount = json.getInt("resend_count"),
        scheme = json.string("scheme"),
        host = json.string("host"),
        port = json.int("port"),
        method = json.string("method"),
        phase = TelemetryPhase.entries.single { it.code == json.getString("phase") },
        type = errorType(json.getString("type")),
        step = json.string("step")?.let(::step),
        statusCode = json.int("status_code"),
        protocol = json.string("protocol"),
        serverIp = json.string("server_ip"),
        proxy = json.string("proxy")?.let(::proxy),
        tls = json.optJSONObject("tls")?.let {
            TelemetryTls(it.getString("version"), it.getString("cipher"), it.string("issuer"))
        },
        reusedConnection = json.getBoolean("reused_connection"),
        dnsAddresses = json.optJSONArray("dns_addresses")?.let { a ->
            List(a.length()) { a.getString(it) }
        },
        startTimeMillis = json.getLong("start_time"),
        elapsedMs = json.getLong("elapsed_time"),
        timings = timings(json.optJSONObject("timings") ?: JSONObject()),
        requestBodyBytes = json.long("request_body_bytes"),
        responseContentLength = json.long("response_content_length"),
        responseBodyBytes = json.long("response_body_bytes"),
        attempts = json.optJSONArray("attempts")
            ?.let { a -> List(a.length()) { attempt(a.getJSONObject(it)) } }
            .orEmpty(),
        network = network(json.getJSONObject("network")),
        foreground = json.bool("foreground")
    )

    fun metadata(json: JSONObject) = UploadMetadata(
        sentAtMillis = json.getLong("sent_at"),
        sdkVersion = json.getString("sdk_version"),
        schemaVersion = json.getString("schema_version"),
        appPackage = json.getString("app_package"),
        appVersion = json.string("app_version"),
        osApiLevel = json.getInt("os_api_level"),
        dropped = json.optJSONObject("dropped")
            ?.let { d ->
                DropReason.entries.filter { d.has(it.code) }.associateWith { d.getLong(it.code) }
            }
            .orEmpty()
    )

    private fun timings(json: JSONObject) = TelemetryTimings(
        json.long("dns"),
        json.long("connect"),
        json.long("tls"),
        json.long("send"),
        json.long("wait"),
        json.long("receive")
    )

    private fun attempt(json: JSONObject) = TelemetryAttempt(
        json.getString("ip"),
        proxy(json.getString("proxy")),
        json.long("elapsed_time"),
        TelemetryAttemptResult.entries.single { it.code == json.getString("result") },
        json.string("type")?.let(::errorType),
        json.string("step")?.let(::step)
    )

    private fun network(json: JSONObject) = when (json.getString("state")) {
        "unknown" -> TelemetryNetwork.Unknown

        "none" -> TelemetryNetwork.None

        else -> TelemetryNetwork.Connected(
            transport = TelemetryNetworkTransport.entries.single {
                it.code ==
                    json.getString("transport")
            },
            vpn = json.getBoolean("vpn"),
            validated = json.getBoolean("validated"),
            captivePortal = json.getBoolean("captive_portal"),
            metered = json.getBoolean("metered"),
            roaming = json.bool("roaming"),
            downstreamKbps = json.int("downstream_kbps"),
            upstreamKbps = json.int("upstream_kbps"),
            signalStrength = json.int("signal_strength"),
            carrier = json.optJSONObject("carrier")?.let {
                TelemetryCarrier(
                    it.string("name"),
                    it.string("mcc"),
                    it.string("mnc"),
                    it.int("carrier_id"),
                    it.int("network_type")
                )
            },
            blocked = json.bool("blocked")
        )
    }

    private fun errorType(code: String) = TelemetryErrorType.entries.single { it.code == code }

    private fun step(code: String) = TelemetryStep.entries.single { it.code == code }

    private fun proxy(code: String) = TelemetryProxyType.entries.single { it.code == code }

    private fun JSONObject.string(key: String) = if (has(key)) getString(key) else null

    private fun JSONObject.int(key: String) = if (has(key)) getInt(key) else null

    private fun JSONObject.long(key: String) = if (has(key)) getLong(key) else null

    private fun JSONObject.bool(key: String) = if (has(key)) getBoolean(key) else null
}
