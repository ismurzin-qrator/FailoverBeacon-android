package net.qrator.failoverbeacon.telemetry.upload

import android.net.TrafficStats
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import net.qrator.failoverbeacon.core.ConsentSource
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.core.time.EpochClock
import net.qrator.failoverbeacon.telemetry.BuildConfig
import net.qrator.failoverbeacon.telemetry.storage.EventStorage

internal class TelemetryUploader(
    private val beacons: List<String>,
    private val storage: EventStorage,
    private val storageExecutor: Executor,
    private val consentSource: ConsentSource,
    private val clock: EpochClock
) {
    private val client = BeaconClient()

    fun upload() {
        TrafficStats.setThreadStatsTag(TRAFFIC_STATS_TAG)
        beacons.forEach(::upload)
    }

    private fun upload(beacon: String) {
        var batches = storage.readyBatches(beacon)
        while (batches.isNotEmpty() && canSend()) {
            val request = nextRequest(batches)
            val events = request.map(storage::read).filter { it.isNotEmpty() }
            if (events.isNotEmpty()) {
                val code = client.post(beacon, body(events))
                SdkRegistry.logger.debug { "Events sent to $beacon: $code" }
                when {
                    code == null || isRetryable(code) -> return

                    code == HTTP_GONE -> {
                        removeBeacon(beacon)
                        return
                    }

                    code !in HTTP_SUCCESS -> SdkRegistry.logger.warn(onlyOnce = true) {
                        "Beacon $beacon rejected events with code $code. Events are dropped"
                    }
                }
            }
            storage.delete(request)
            batches = batches.drop(request.size)
        }
    }

    private fun isRetryable(code: Int): Boolean =
        code == HTTP_CLIENT_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code in HTTP_SERVER_ERROR

    private fun removeBeacon(beacon: String) {
        storageExecutor.execute { storage.removeBeacon(beacon) }
        SdkRegistry.logger.warn(onlyOnce = true) {
            "Beacon $beacon is gone. Events are no longer sent to it"
        }
    }

    private fun canSend(): Boolean =
        consentSource.consent == TrackingConsent.GRANTED && !Thread.currentThread().isInterrupted

    private fun nextRequest(batches: List<File>): List<File> {
        var size = 0L
        return batches.takeWhile { batch ->
            size += batch.length()
            size <= MAX_REQUEST_BYTES || batch === batches.first()
        }
    }

    private fun body(events: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        val metadata = """{"sent_at":${clock.nowMillis()},"sdk_version":"$SDK_VERSION"}"""
        body.write((metadata + '\n').toByteArray(Charsets.UTF_8))
        events.forEach(body::write)
        return body.toByteArray()
    }

    private companion object {
        const val SDK_VERSION = BuildConfig.SDK_VERSION
        const val TRAFFIC_STATS_TAG = 0x4642 // FB
        const val MAX_REQUEST_BYTES = 1024L * 1024
        const val HTTP_CLIENT_TIMEOUT = 408
        const val HTTP_GONE = 410
        const val HTTP_TOO_MANY_REQUESTS = 429
        val HTTP_SUCCESS = 200..299
        val HTTP_SERVER_ERROR = 500..599
    }
}
