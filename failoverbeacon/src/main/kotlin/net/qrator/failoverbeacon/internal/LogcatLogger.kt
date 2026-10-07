package net.qrator.failoverbeacon.internal

import android.util.Log
import net.qrator.failoverbeacon.core.logging.LogOutput

internal object LogcatLogger : LogOutput {
    private const val TAG = "FailoverBeacon"

    override fun log(level: Int, message: String, throwable: Throwable?) {
        when (level) {
            Log.VERBOSE -> Log.v(TAG, message, throwable)
            Log.DEBUG -> Log.d(TAG, message, throwable)
            Log.INFO -> Log.i(TAG, message, throwable)
            Log.WARN -> Log.w(TAG, message, throwable)
            else -> Log.e(TAG, message, throwable)
        }
    }
}
