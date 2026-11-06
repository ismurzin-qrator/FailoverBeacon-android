package net.qrator.failoverbeacon

import java.util.concurrent.TimeUnit

/**
 * Fine-tuning options for [FailoverBeaconConfig]. The defaults suit most apps.
 *
 * Instances are immutable; create them with [Builder] and pass them to
 * [FailoverBeaconConfig.Builder.options].
 */
public class FailoverBeaconOptions private constructor(
    internal val uploadPeriodMillis: Long,
    internal val diskQueueBytes: Long,
    internal val memoryQueueSize: Int,
    internal val preresolveOnNetworkChange: Boolean,
    internal val probeOnNetworkChange: Boolean,
    internal val refreshIntervalMillis: Long,
    internal val fallbackWithoutEch: Boolean
) {
    /** Builds [FailoverBeaconOptions]. Every setter is optional. */
    public class Builder {
        private var uploadPeriodMillis = DEFAULT_UPLOAD_PERIOD_MILLIS
        private var diskQueueBytes = DEFAULT_DISK_QUEUE_BYTES
        private var memoryQueueSize = DEFAULT_MEMORY_QUEUE_SIZE
        private var preresolveOnNetworkChange = true
        private var probeOnNetworkChange = true
        private var refreshIntervalMillis = DEFAULT_REFRESH_INTERVAL_MILLIS
        private var fallbackWithoutEch = true

        /**
         * Sets how often stored events are uploaded, from 1 second to 8 hours. The default value
         * is 60 seconds.
         *
         * @throws IllegalArgumentException if [period] is shorter than 1 second or longer than
         *     8 hours.
         */
        public fun uploadPeriod(period: Long, unit: TimeUnit): Builder = apply {
            val millis = unit.toMillis(period)
            require(millis in MIN_UPLOAD_PERIOD_MILLIS..MAX_UPLOAD_PERIOD_MILLIS) {
                "uploadPeriod must be from 1 second to 8 hours, was $period $unit"
            }
            uploadPeriodMillis = millis
        }

        /**
         * Sets the maximum size in bytes of the events stored on disk, shared equally by the
         * beacons. When a beacon's share is full, its oldest events are dropped. Events older than
         * 18 hours are dropped as well. The default value is 5 MB.
         *
         * @throws IllegalArgumentException if [bytes] is not positive.
         */
        public fun diskQueueBytes(bytes: Long): Builder = apply {
            require(bytes > 0) { "diskQueueBytes must be > 0, was $bytes" }
            diskQueueBytes = bytes
        }

        /**
         * Sets the maximum number of events waiting in memory to be written to disk. When the
         * queue is full, new events are dropped. The default value is 1000.
         *
         * @throws IllegalArgumentException if [size] is not positive.
         */
        public fun memoryQueueSize(size: Int): Builder = apply {
            require(size > 0) { "memoryQueueSize must be > 0, was $size" }
            memoryQueueSize = size
        }

        /**
         * Sets whether failover hosts are resolved in the background when the default network
         * changes. When disabled, the first new connection to a host on that network waits for
         * DNS. The default value is true.
         */
        public fun preresolveOnNetworkChange(enabled: Boolean): Builder = apply {
            preresolveOnNetworkChange = enabled
        }

        /**
         * Sets whether the addresses of failover hosts are tested with connections when the
         * default network changes. When disabled, they are tested only on the network the SDK
         * started on. The default value is true.
         */
        public fun probeOnNetworkChange(enabled: Boolean): Builder = apply {
            probeOnNetworkChange = enabled
        }

        /**
         * Sets how often failover hosts are resolved and their addresses tested with connections
         * while the app makes requests, from 1 minute to 1 hour. A new connection to a host with
         * an older DNS answer waits for DNS up to 2 seconds. The default value is 10 minutes.
         *
         * @throws IllegalArgumentException if [interval] is shorter than 1 minute or longer than
         *     1 hour.
         */
        public fun refreshInterval(interval: Long, unit: TimeUnit): Builder = apply {
            val millis = unit.toMillis(interval)
            require(millis in MIN_REFRESH_INTERVAL_MILLIS..MAX_REFRESH_INTERVAL_MILLIS) {
                "refreshInterval must be from 1 minute to 1 hour, was $interval $unit"
            }
            refreshIntervalMillis = millis
        }

        /**
         * Sets whether, on Android 17 and later with a client DNS that supports ECH, a new
         * connection to a failover host uses the SDK addresses without an ECH key while the
         * client's DNS has not answered for that host. When disabled, such calls fail as they would
         * without the SDK, and the host name is not sent in clear text. The default value is true.
         */
        public fun fallbackWithoutEch(enabled: Boolean): Builder = apply {
            fallbackWithoutEch = enabled
        }

        /** Creates the options. */
        public fun build(): FailoverBeaconOptions = FailoverBeaconOptions(
            uploadPeriodMillis,
            diskQueueBytes,
            memoryQueueSize,
            preresolveOnNetworkChange,
            probeOnNetworkChange,
            refreshIntervalMillis,
            fallbackWithoutEch
        )

        private companion object {
            private val DEFAULT_UPLOAD_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(60)
            private val MIN_UPLOAD_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(1)
            private val MAX_UPLOAD_PERIOD_MILLIS = TimeUnit.HOURS.toMillis(8)
            private const val DEFAULT_DISK_QUEUE_BYTES = 5L * 1024 * 1024
            private const val DEFAULT_MEMORY_QUEUE_SIZE = 1000
            private val DEFAULT_REFRESH_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10)
            private val MIN_REFRESH_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1)
            private val MAX_REFRESH_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1)
        }
    }
}
