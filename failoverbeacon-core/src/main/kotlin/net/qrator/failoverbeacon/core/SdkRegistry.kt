package net.qrator.failoverbeacon.core

import net.qrator.failoverbeacon.core.logging.InternalLogger

/** Process-wide state of the SDK. */
object SdkRegistry {
    /** Logger for all SDK code. */
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

    /** Forgets the running SDK. */
    @Synchronized
    fun clear() {
        instance = null
    }
}
