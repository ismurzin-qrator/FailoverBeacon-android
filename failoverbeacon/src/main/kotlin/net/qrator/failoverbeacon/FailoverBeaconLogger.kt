package net.qrator.failoverbeacon

/**
 * Receives log messages from the SDK in place of Logcat.
 *
 * Messages never contain request paths, query strings, headers, or bodies. Warnings may name a
 * beacon host. At [android.util.Log.DEBUG] the SDK also logs each event selected for storage, in
 * full: host names, IP addresses, DNS answers, TLS details, network type and carrier.
 * DEBUG also includes queue and upload operations, and the DNS answers and probe results of
 * failover hosts with their IP addresses.
 */
public fun interface FailoverBeaconLogger {
    /**
     * Logs [message] at priority [level], one of the [android.util.Log] constants from
     * [android.util.Log.VERBOSE] to [android.util.Log.ASSERT]. [throwable] is the error that caused
     * the message, or null.
     */
    public fun log(level: Int, message: String, throwable: Throwable?)
}
