package net.qrator.failoverbeacon.failover.fakes

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput

class LogRecorder : AutoCloseable {
    private val previousOutput = SdkRegistry.logger.output
    private val previousLevel = SdkRegistry.logger.minLevel
    val messages = CopyOnWriteArrayList<Pair<Int, String>>()

    init {
        SdkRegistry.logger.output = LogOutput { level, message, _ -> messages += level to message }
        SdkRegistry.logger.minLevel = Log.DEBUG
    }

    override fun close() {
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.logger.minLevel = previousLevel
    }
}
