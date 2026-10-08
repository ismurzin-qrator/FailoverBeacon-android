package net.qrator.failoverbeacon.okhttp.internal

/** Allows the annotated function to call OkHttp API added in OkHttp [since], newer than 3.14. */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
internal annotation class SuppressSignatureCheck(val since: String)
