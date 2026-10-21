package net.qrator.failoverbeacon.core.app

fun interface ForegroundSource {
    fun isForeground(): Boolean?
}
