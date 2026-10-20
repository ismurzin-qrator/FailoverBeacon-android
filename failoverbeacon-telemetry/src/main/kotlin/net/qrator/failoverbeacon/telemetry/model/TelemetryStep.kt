package net.qrator.failoverbeacon.telemetry.model

/** Steps of an exchange in the order they happen. */
internal enum class TelemetryStep { DNS, TCP, TLS, REQUEST, RESPONSE }
