package net.qrator.failoverbeacon.core.call

import net.qrator.failoverbeacon.core.call.model.Exchange

fun interface ExchangePublisher {
    fun publish(exchange: Exchange)
}
