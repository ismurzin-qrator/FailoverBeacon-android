package net.qrator.failoverbeacon.core.call.model

/** Steps of an exchange in the order they happen. */
enum class ExchangeStep { DNS, TCP, TLS, REQUEST, RESPONSE }
