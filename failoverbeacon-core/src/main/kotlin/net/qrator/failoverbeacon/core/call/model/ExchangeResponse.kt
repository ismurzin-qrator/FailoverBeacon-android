package net.qrator.failoverbeacon.core.call.model

/**
 * [bodyBytes] counts the body read until it was closed: part of it if it was closed early, 0 if it
 * was discarded unread and null if reading it failed.
 */
data class ExchangeResponse(val statusCode: Int, val contentLength: Long?, val bodyBytes: Long?)
