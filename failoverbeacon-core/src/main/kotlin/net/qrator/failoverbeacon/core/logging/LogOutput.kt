package net.qrator.failoverbeacon.core.logging

/** Receives the messages that pass the filter of [InternalLogger]. */
fun interface LogOutput {
    fun log(level: Int, message: String, throwable: Throwable?)
}
