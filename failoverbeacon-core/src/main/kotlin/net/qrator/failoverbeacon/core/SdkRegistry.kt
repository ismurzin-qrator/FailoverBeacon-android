package net.qrator.failoverbeacon.core

import net.qrator.failoverbeacon.core.logging.InternalLogger

object SdkRegistry {
    val logger: InternalLogger = InternalLogger()

    /** The running SDK; null until it is installed. */
    @Volatile
    var instance: SdkInstance? = null
        private set

    /** Sets [instance] once; returns false if it is already set. */
    @Synchronized
    fun install(instance: SdkInstance): Boolean {
        if (this.instance != null) return false
        this.instance = instance
        return true
    }

    /** Stops and forgets the running SDK, forgets the messages logged once. */
    @Synchronized
    fun clear() {
        instance?.close()
        instance = null
        logger.forgetLoggedOnce()
    }
}
