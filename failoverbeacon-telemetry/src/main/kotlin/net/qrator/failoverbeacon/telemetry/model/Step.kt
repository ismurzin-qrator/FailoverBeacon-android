package net.qrator.failoverbeacon.telemetry.model

/** Steps of an exchange in the order they happen. */
internal enum class Step { DNS, TCP, TLS, REQUEST, RESPONSE }
