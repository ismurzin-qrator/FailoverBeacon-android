package net.qrator.failoverbeacon.core.call

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure

/** Delivers each finished exchange to every subscriber until it is closed. */
class ExchangeBus :
    ExchangePublisher,
    Closeable {
    private val subscribers = CopyOnWriteArrayList<ExchangeSubscriber>()

    val hasSubscribers: Boolean get() = subscribers.isNotEmpty()

    fun subscribe(subscriber: ExchangeSubscriber) {
        subscribers += subscriber
    }

    override fun close() {
        subscribers.clear()
    }

    override fun publish(exchange: Exchange) {
        for (subscriber in subscribers) {
            catchSdkFailure(::logFailure) { subscriber.onExchange(exchange) }
        }
    }

    private fun logFailure(failure: Throwable) {
        SdkRegistry.logger.error(failure, onlyOnce = true) {
            "[Core] Exchange subscriber failed. Further failures are not logged"
        }
    }
}
