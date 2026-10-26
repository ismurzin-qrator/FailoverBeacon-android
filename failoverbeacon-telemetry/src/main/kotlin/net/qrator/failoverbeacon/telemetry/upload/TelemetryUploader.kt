package net.qrator.failoverbeacon.telemetry.upload

import android.net.TrafficStats
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.Executor
import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.core.network.NetworkSource
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.core.time.EpochClock
import net.qrator.failoverbeacon.telemetry.BuildConfig
import net.qrator.failoverbeacon.telemetry.DropReason
import net.qrator.failoverbeacon.telemetry.DroppedEvents
import net.qrator.failoverbeacon.telemetry.storage.EventStorage

internal class TelemetryUploader(
    private val beacons: List<String>,
    private val storage: EventStorage,
    private val storageExecutor: Executor,
    private val consentSource: ConsentSource,
    private val networkSource: NetworkSource,
    private val clock: EpochClock,
    private val dropped: DroppedEvents
) {
    private val client = BeaconClient()
    private val backoff = beacons.associateWith { BeaconBackoff() }
    private val hosts = beacons.associateWith { URI(it).host }
    private var pass = 0L

    fun upload() {
        TrafficStats.setThreadStatsTag(TRAFFIC_STATS_TAG)
        pass++
        beacons.forEach(::upload)
    }

    private fun upload(beacon: String) {
        val backoff = backoff.getValue(beacon)
        if (!backoff.canAttempt(pass)) return
        var batches = storage.readyBatches(beacon)
        while (batches.isNotEmpty() && canSend()) {
            val request = nextRequest(batches)
            val events = request.map(storage::read).filter { it.isNotEmpty() }
            if (events.isNotEmpty()) {
                val reported = dropped.snapshot(beacon)
                val code = client.post(beacon, body(events, reported))
                SdkRegistry.logger.debug { "Events sent to ${hosts[beacon]}: $code" }
                when {
                    code == null || isRetryable(code) -> {
                        backoff.onFailure(pass)
                        return
                    }

                    code == HTTP_GONE -> {
                        removeBeacon(beacon)
                        return
                    }

                    code in HTTP_SUCCESS -> {
                        dropped.subtract(beacon, reported)
                        backoff.onSuccess()
                    }

                    else -> reject(beacon, code, events)
                }
            }
            storage.delete(request)
            batches = batches.drop(request.size)
        }
    }

    private fun isRetryable(code: Int): Boolean =
        code == HTTP_CLIENT_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code in HTTP_SERVER_ERROR

    private fun reject(beacon: String, code: Int, events: List<ByteArray>) {
        dropped.add(beacon, DropReason.SEND_ERROR, events.sumOf { it.lineCount() }.toLong())
        SdkRegistry.logger.warn(onlyOnce = true) {
            "Beacon ${hosts[beacon]} rejected events with code $code. Events are dropped"
        }
    }

    private fun ByteArray.lineCount(): Int = count { it == NEW_LINE }

    private fun removeBeacon(beacon: String) {
        storageExecutor.execute { storage.removeBeacon(beacon) }
        SdkRegistry.logger.warn(onlyOnce = true) {
            "Beacon ${hosts[beacon]} is gone. Events are no longer sent to it"
        }
    }

    private fun canSend(): Boolean = consentSource.consent == TrackingConsent.GRANTED &&
        hasNetwork() &&
        !Thread.currentThread().isInterrupted

    private fun hasNetwork(): Boolean = when (val network = networkSource.currentNetwork()) {
        NetworkSnapshot.None -> false
        NetworkSnapshot.Unknown -> true
        is NetworkSnapshot.Connected -> network.blocked != true
    }

    private fun nextRequest(batches: List<File>): List<File> {
        var size = 0L
        return batches.takeWhile { batch ->
            size += batch.length()
            size <= MAX_REQUEST_BYTES || batch === batches.first()
        }
    }

    private fun body(events: List<ByteArray>, reported: Map<DropReason, Long>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((metadata(reported) + '\n').toByteArray(Charsets.UTF_8))
        events.forEach(body::write)
        return body.toByteArray()
    }

    private fun metadata(reported: Map<DropReason, Long>): String {
        val droppedField = if (reported.isEmpty()) {
            ""
        } else {
            reported.entries.joinToString(
                ",",
                prefix = ",\"dropped\":{",
                postfix = "}"
            ) { (reason, count) ->
                "\"${reason.code}\":$count"
            }
        }
        return """{"sent_at":${clock.nowMillis()},"sdk_version":"$SDK_VERSION"$droppedField}"""
    }

    private companion object {
        const val SDK_VERSION = BuildConfig.SDK_VERSION
        const val TRAFFIC_STATS_TAG = 0x4642 // FB
        const val MAX_REQUEST_BYTES = 1024L * 1024
        const val NEW_LINE = '\n'.code.toByte()
        const val HTTP_CLIENT_TIMEOUT = 408
        const val HTTP_GONE = 410
        const val HTTP_TOO_MANY_REQUESTS = 429
        val HTTP_SUCCESS = 200..299
        val HTTP_SERVER_ERROR = 500..599
    }
}
