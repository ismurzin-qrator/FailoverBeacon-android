package net.qrator.failoverbeacon.core.app

import java.io.Closeable

fun interface ForegroundSource : Closeable {
    fun isForeground(): Boolean?

    override fun close() = Unit
}
