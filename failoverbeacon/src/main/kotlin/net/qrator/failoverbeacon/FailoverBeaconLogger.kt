package net.qrator.failoverbeacon

/**
 * Receives log messages from the SDK in place of Logcat.
 *
 * Messages never contain request paths, query strings, headers, or bodies. At
 * [android.util.Log.DEBUG] they may contain host names, server IP addresses, and error types.
 */
public fun interface FailoverBeaconLogger {
    /**
     * Logs [message] at priority [level], one of the [android.util.Log] constants from
     * [android.util.Log.VERBOSE] to [android.util.Log.ASSERT]. [throwable] is the error that caused
     * the message, or null.
     */
    public fun log(level: Int, message: String, throwable: Throwable?)
}
