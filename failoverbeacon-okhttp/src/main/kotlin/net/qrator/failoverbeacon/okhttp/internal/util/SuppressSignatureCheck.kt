package net.qrator.failoverbeacon.okhttp.internal.util

/** Allows the annotated code to use OkHttp API added in OkHttp [since], newer than 3.14. */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
internal annotation class SuppressSignatureCheck(val since: String)
