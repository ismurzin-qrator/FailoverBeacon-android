package net.qrator.failoverbeacon.core.call.mapper

import net.qrator.failoverbeacon.core.call.model.FailureType

fun interface FailureMapper {
    fun toFailureType(failure: Throwable): FailureType
}
