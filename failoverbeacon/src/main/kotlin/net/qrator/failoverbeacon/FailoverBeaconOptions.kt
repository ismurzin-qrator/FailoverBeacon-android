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
    internal val diskQueueSize: Int,
    internal val memoryQueueSize: Int
) {
    /** Builds [FailoverBeaconOptions]. Every setter is optional. */
    public class Builder {
        private var uploadPeriodMillis = DEFAULT_UPLOAD_PERIOD_MILLIS
        private var diskQueueSize = DEFAULT_DISK_QUEUE_SIZE
        private var memoryQueueSize = DEFAULT_MEMORY_QUEUE_SIZE

        /**
         * Sets how often stored events are uploaded. The default value is 60 seconds.
         *
         * @throws IllegalArgumentException if [period] is shorter than 1 millisecond.
         */
        public fun uploadPeriod(period: Long, unit: TimeUnit): Builder = apply {
            val millis = unit.toMillis(period)
            require(millis > 0) { "uploadPeriod must be at least 1 ms, was $period $unit" }
            uploadPeriodMillis = millis
        }

        /**
         * Sets the maximum number of events stored on disk. When the queue is full, the oldest
         * events are dropped. The default value is 1000.
         *
         * @throws IllegalArgumentException if [size] is not positive.
         */
        public fun diskQueueSize(size: Int): Builder = apply {
            require(size > 0) { "diskQueueSize must be > 0, was $size" }
            diskQueueSize = size
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

        /** Creates the options. */
        public fun build(): FailoverBeaconOptions =
            FailoverBeaconOptions(uploadPeriodMillis, diskQueueSize, memoryQueueSize)

        private companion object {
            private val DEFAULT_UPLOAD_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(60)
            private const val DEFAULT_DISK_QUEUE_SIZE = 1000
            private const val DEFAULT_MEMORY_QUEUE_SIZE = 1000
        }
    }
}
