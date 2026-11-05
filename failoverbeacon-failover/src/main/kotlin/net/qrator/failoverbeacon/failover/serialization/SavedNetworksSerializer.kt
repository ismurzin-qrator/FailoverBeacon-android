package net.qrator.failoverbeacon.failover.serialization

import java.net.InetAddress
import net.qrator.failoverbeacon.failover.model.SavedAddress
import net.qrator.failoverbeacon.failover.model.SavedBodyProbe
import net.qrator.failoverbeacon.failover.model.SavedHost
import net.qrator.failoverbeacon.failover.model.SavedNetwork
import org.json.JSONArray
import org.json.JSONObject

internal class SavedNetworksSerializer {
    fun toJson(networks: List<SavedNetwork>): String = JSONObject()
        .put(VERSION, CURRENT_VERSION)
        .put(NETWORKS, networks.toJsonArray(::networkToJson))
        .toString()

    fun fromJson(json: String): List<SavedNetwork> {
        val root = JSONObject(json)
        if (root.getInt(VERSION) != CURRENT_VERSION) return emptyList()
        return root.getJSONArray(NETWORKS).map(::networkFromJson)
    }

    private fun networkToJson(network: SavedNetwork): JSONObject = JSONObject()
        .put(KEY, network.key)
        .put(HOSTS, network.hosts.toJsonArray(::hostToJson))

    private fun networkFromJson(json: JSONObject) = SavedNetwork(
        key = json.getString(KEY),
        hosts = json.getJSONArray(HOSTS).map(::hostFromJson)
    )

    private fun hostToJson(host: SavedHost): JSONObject = JSONObject()
        .put(HOST, host.host)
        .put(ANSWERED_AT, host.answeredAtMillis)
        .put(ADDRESSES, host.addresses.toJsonArray(::addressToJson))

    private fun hostFromJson(json: JSONObject): SavedHost {
        val host = json.getString(HOST)
        return SavedHost(
            host = host,
            answeredAtMillis = json.longOrNull(ANSWERED_AT),
            addresses = json.getJSONArray(ADDRESSES).map { addressFromJson(host, it) }
        )
    }

    private fun addressToJson(saved: SavedAddress): JSONObject = JSONObject()
        .put(IP, saved.address.address.joinToString("") { HEX_BYTE.format(it) })
        .put(IS_FROM_DNS, saved.isFromDns)
        .put(TCP_NANOS, saved.tcpNanos)
        .put(BODY_PASSED, saved.bodyProbe?.isPassed)
        .put(BODY_AT, saved.bodyProbe?.atMillis)

    private fun addressFromJson(host: String, json: JSONObject) = SavedAddress(
        address = InetAddress.getByAddress(host, json.getString(IP).hexToBytes()),
        isFromDns = json.getBoolean(IS_FROM_DNS),
        tcpNanos = json.longOrNull(TCP_NANOS),
        bodyProbe = json.longOrNull(BODY_AT)?.let { atMillis ->
            SavedBodyProbe(json.getBoolean(BODY_PASSED), atMillis)
        }
    )

    private fun <T> List<T>.toJsonArray(toJson: (T) -> JSONObject): JSONArray =
        fold(JSONArray()) { array, item -> array.put(toJson(item)) }

    private fun <T> JSONArray.map(fromJson: (JSONObject) -> T): List<T> =
        List(length()) { fromJson(getJSONObject(it)) }

    private fun JSONObject.longOrNull(name: String): Long? = if (has(name)) getLong(name) else null

    private fun String.hexToBytes(): ByteArray =
        chunked(HEX_BYTE_LENGTH).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()

    private companion object {
        const val VERSION = "version"
        const val NETWORKS = "networks"
        const val KEY = "key"
        const val HOSTS = "hosts"
        const val HOST = "host"
        const val ANSWERED_AT = "answeredAtMillis"
        const val ADDRESSES = "addresses"
        const val IP = "ip"
        const val IS_FROM_DNS = "fromDns"
        const val TCP_NANOS = "tcpNanos"
        const val BODY_PASSED = "bodyPassed"
        const val BODY_AT = "bodyProbedAtMillis"
        const val CURRENT_VERSION = 1
        const val HEX_BYTE = "%02x"
        const val HEX_BYTE_LENGTH = 2
        const val HEX_RADIX = 16
    }
}
