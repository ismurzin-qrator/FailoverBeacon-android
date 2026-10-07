package net.qrator.failoverbeacon.core.logging

import android.util.Log

/**
 * Logger for the SDK's own messages. Levels are [android.util.Log] constants.
 *
 * Messages below [minLevel] or without an [output] are dropped; exceptions thrown by [output] are
 * ignored. Log through [debug], [info], [warn], and [error]: they build the message only when the
 * level is enabled.
 */
class InternalLogger {
    @Volatile
    var minLevel: Int = Log.WARN
        set(value) {
            require(value in Log.VERBOSE..Log.ASSERT) {
                "level must be an android.util.Log constant from VERBOSE to ASSERT, was $value"
            }
            field = value
        }

    @Volatile
    var output: LogOutput? = null

    fun isLoggable(level: Int): Boolean = output != null && level >= minLevel

    fun log(level: Int, message: String, throwable: Throwable? = null) {
        val output = output ?: return
        if (level < minLevel) return
        try {
            output.log(level, message, throwable)
        } catch (_: Exception) {
            // NO-OP: an output must not break the code that logs.
        }
    }
}

inline fun InternalLogger.debug(throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(Log.DEBUG)) log(Log.DEBUG, message(), throwable)
}

inline fun InternalLogger.info(throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(Log.INFO)) log(Log.INFO, message(), throwable)
}

inline fun InternalLogger.warn(throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(Log.WARN)) log(Log.WARN, message(), throwable)
}

inline fun InternalLogger.error(throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(Log.ERROR)) log(Log.ERROR, message(), throwable)
}
