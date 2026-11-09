package net.qrator.failoverbeacon.okhttp.fakes

import okhttp3.Interceptor

val isRetryControlSupported: Boolean = runCatching {
    Interceptor.Chain::class.java.getMethod(
        "withRetryOnConnectionFailure",
        Boolean::class.javaPrimitiveType
    )
}.isSuccess
