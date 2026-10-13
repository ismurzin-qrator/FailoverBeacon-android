package net.qrator.failoverbeacon.core.util

internal class Interval(var startNanos: Long? = null) {
    var endNanos: Long? = null

    val durationNanos: Long?
        get() {
            val start = startNanos ?: return null
            val end = endNanos ?: return null
            return if (end < start) null else end - start
        }

    fun contains(time: Long): Boolean {
        val start = startNanos ?: return false
        return start < time && time < (endNanos ?: Long.MAX_VALUE)
    }

    fun overlaps(other: Interval): Boolean {
        val start = startNanos ?: return false
        val otherStart = other.startNanos ?: return false
        val end = endNanos ?: Long.MAX_VALUE
        val otherEnd = other.endNanos ?: Long.MAX_VALUE
        return start < otherEnd && end > otherStart
    }
}
