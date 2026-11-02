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
    internal val probeOnNetworkChange: Boolean
) {
    /** Builds [FailoverBeaconOptions]. Every setter is optional. */
    public class Builder {
        private var uploadPeriodMillis = DEFAULT_UPLOAD_PERIOD_MILLIS
        private var diskQueueBytes = DEFAULT_DISK_QUEUE_BYTES
        private var memoryQueueSize = DEFAULT_MEMORY_QUEUE_SIZE
        private var preresolveOnNetworkChange = true
        private var probeOnNetworkChange = true

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

        /** Creates the options. */
        public fun build(): FailoverBeaconOptions = FailoverBeaconOptions(
            uploadPeriodMillis,
            diskQueueBytes,
            memoryQueueSize,
            preresolveOnNetworkChange,
            probeOnNetworkChange
        )

        private companion object {
            private val DEFAULT_UPLOAD_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(60)
            private val MIN_UPLOAD_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(1)
            private val MAX_UPLOAD_PERIOD_MILLIS = TimeUnit.HOURS.toMillis(8)
            private const val DEFAULT_DISK_QUEUE_BYTES = 5L * 1024 * 1024
            private const val DEFAULT_MEMORY_QUEUE_SIZE = 1000
        }
    }
}
