package net.qrator.failoverbeacon.telemetry.model

internal data class EventTls(val version: String, val cipher: String, val issuer: String?)
