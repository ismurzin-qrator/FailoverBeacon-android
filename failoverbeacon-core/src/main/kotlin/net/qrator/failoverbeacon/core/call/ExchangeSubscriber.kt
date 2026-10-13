package net.qrator.failoverbeacon.core.call

import net.qrator.failoverbeacon.core.call.model.Exchange

fun interface ExchangeSubscriber {
    fun onExchange(exchange: Exchange)
}
