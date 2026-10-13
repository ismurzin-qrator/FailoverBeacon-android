package net.qrator.failoverbeacon.core.call.model

/** [bodyBytes] is null unless the body was read to its end. */
data class ExchangeResponse(val statusCode: Int, val contentLength: Long?, val bodyBytes: Long?)
