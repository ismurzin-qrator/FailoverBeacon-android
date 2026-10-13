package net.qrator.failoverbeacon.core.call.model

/** Target and method are null for a follow-up that failed before OkHttp named them. */
data class ExchangeRequest(
    val serverAddress: String?,
    val serverPort: Int?,
    val method: String?,
    val bodyBytes: Long?
)
