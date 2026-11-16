package net.qrator.failoverbeacon.telemetry.serialization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 37])
class TelemetryEventSerializerTest {
    private val serializer = TelemetryEventSerializer()

    @Test
    fun `writes every field on one line`() {
        val event = MINIMAL.copy(
            callId = 7,
            scheme = "https",
            host = "a.com",
            port = 443,
            method = "POST",
            phase = TelemetryPhase.CONNECTION,
            type = TelemetryErrorType.TLS_CERT_AUTHORITY_INVALID,
            step = TelemetryStep.TLS,
            statusCode = 200,
            protocol = "h2",
            serverIp = "1.1.1.1",
            proxy = TelemetryProxyType.HTTP,
            tls = TelemetryTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA"),
            reusedConnection = true,
            dnsAddresses = listOf("1.1.1.1", "1.1.1.2"),
            timings = TelemetryTimings(1, 20, 12, 2, 30, 4),
            requestBodyBytes = 3,
            responseContentLength = 5,
            responseBodyBytes = 5,
            attempts = listOf(
                TelemetryAttempt(
                    "1.1.1.1",
                    TelemetryProxyType.SOCKS,
                    40,
                    TelemetryAttemptResult.FAILED,
                    TelemetryErrorType.TLS_CERT_AUTHORITY_INVALID,
                    TelemetryStep.TLS
                ),
                TelemetryAttempt(
                    "1.1.1.2",
                    TelemetryProxyType.DIRECT,
                    null,
                    TelemetryAttemptResult.CANCELED,
                    type = null,
                    step = null
                )
            ),
            network = TelemetryNetwork.Connected(
                transport = TelemetryNetworkTransport.CELLULAR,
                vpn = true,
                validated = true,
                captivePortal = false,
                metered = true,
                roaming = false,
                downstreamKbps = 30_000,
                upstreamKbps = 10_000,
                signalStrength = -90,
                carrier = TelemetryCarrier("Carrier B", "001", "02", 2, 13),
                blocked = false
            ),
            foreground = true,
            failover = null
        )

        assertEquals(
            """{"format_version":1,"call_id":"0000000000000007","resend_count":1,""" +
                """"start_time":1761040980000,"elapsed_time":60,"sampling_fraction":0.1,""" +
                """"scheme":"https","host":"a.com","port":443,"method":"POST",""" +
                """"phase":"connection","type":"tls.cert.authority_invalid","step":"tls",""" +
                """"status_code":200,"protocol":"h2","server_ip":"1.1.1.1","proxy":"http",""" +
                """"tls":{"version":"TLSv1.3","cipher":"TLS_AES_128_GCM_SHA256",""" +
                """"issuer":"CN=Test CA"},"reused_connection":true,""" +
                """"dns_addresses":["1.1.1.1","1.1.1.2"],""" +
                """"timings":{"dns":1,"connect":20,"tls":12,"send":2,"wait":30,"receive":4},""" +
                """"request_body_bytes":3,"response_content_length":5,"response_body_bytes":5,""" +
                """"attempts":[{"ip":"1.1.1.1","proxy":"socks","elapsed_time":40,""" +
                """"result":"failed","type":"tls.cert.authority_invalid","step":"tls"},""" +
                """{"ip":"1.1.1.2","proxy":"direct","result":"canceled"}],""" +
                """"network":{"state":"connected","transport":"cellular","vpn":true,""" +
                """"validated":true,"captive_portal":false,"metered":true,"roaming":false,""" +
                """"downstream_kbps":30000,"upstream_kbps":10000,"signal_strength":-90,""" +
                """"blocked":false,"carrier":{"name":"Carrier B","mcc":"001","mnc":"02",""" +
                """"carrier_id":2,"network_type":13}},"foreground":true}""",
            serializer.toJson(event, samplingFraction = 0.1)
        )
    }

    @Test
    fun `leaves out what is unknown`() {
        assertEquals(
            """{"format_version":1,"call_id":"ffffffffffffffff","resend_count":1,""" +
                """"start_time":1761040980000,"elapsed_time":60,"sampling_fraction":1.0,""" +
                """"phase":"application","type":"abandoned","reused_connection":false,""" +
                """"network":{"state":"unknown"}}""",
            serializer.toJson(MINIMAL, samplingFraction = 1.0)
        )
    }

    @Test
    fun `writes only the phases that happened`() {
        val event = MINIMAL.copy(timings = TelemetryTimings(null, null, null, 2, 30, 4))

        val json = JSONObject(serializer.toJson(event, 1.0))

        assertEquals(
            """{"send":2,"wait":30,"receive":4}""",
            json.getJSONObject("timings").toString()
        )
    }

    @Test
    fun `tells no network from an unknown one`() {
        val json = JSONObject(serializer.toJson(MINIMAL.copy(network = TelemetryNetwork.None), 1.0))

        assertEquals("""{"state":"none"}""", json.getJSONObject("network").toString())
    }

    @Test
    fun `escapes strings and keeps the event on one line`() {
        val name = "Оператор \"A\" \\ \u0001\n"
        val event = MINIMAL.copy(
            network = TelemetryNetwork.Connected(
                transport = TelemetryNetworkTransport.WIFI,
                vpn = false,
                validated = true,
                captivePortal = false,
                metered = false,
                roaming = null,
                downstreamKbps = null,
                upstreamKbps = null,
                signalStrength = null,
                carrier = TelemetryCarrier(name, null, null, null, null),
                blocked = null
            )
        )

        val line = serializer.toJson(event, 1.0)

        val carrier = JSONObject(line).getJSONObject("network").getJSONObject("carrier")
        assertEquals(name, carrier.getString("name"))
        assertFalse('\n' in line)
    }

    @Test
    fun `sends a fixed code for every value`() {
        assertEquals(
            listOf("dns", "connection", "application"),
            TelemetryPhase.entries.map { it.code }
        )
        assertEquals(
            listOf("dns", "tcp", "tls", "request", "response"),
            TelemetryStep.entries.map { it.code }
        )
        assertEquals(
            listOf("direct", "http", "socks"),
            TelemetryProxyType.entries.map { it.code }
        )
        assertEquals(
            listOf("wifi", "ethernet", "cellular", "bluetooth", "other"),
            TelemetryNetworkTransport.entries.map { it.code }
        )
        assertEquals(
            listOf("connected", "failed", "canceled"),
            TelemetryAttemptResult.entries.map { it.code }
        )
    }

    private companion object {
        val MINIMAL = TelemetryEvent(
            callId = -1,
            resendCount = 1,
            scheme = null,
            host = null,
            port = null,
            method = null,
            phase = TelemetryPhase.APPLICATION,
            type = TelemetryErrorType.ABANDONED,
            step = null,
            statusCode = null,
            protocol = null,
            serverIp = null,
            proxy = null,
            tls = null,
            reusedConnection = false,
            dnsAddresses = null,
            startTimeMillis = 1_761_040_980_000,
            elapsedMs = 60,
            timings = TelemetryTimings(null, null, null, null, null, null),
            requestBodyBytes = null,
            responseContentLength = null,
            responseBodyBytes = null,
            attempts = emptyList(),
            network = TelemetryNetwork.Unknown,
            foreground = null,
            failover = null
        )
    }
}
