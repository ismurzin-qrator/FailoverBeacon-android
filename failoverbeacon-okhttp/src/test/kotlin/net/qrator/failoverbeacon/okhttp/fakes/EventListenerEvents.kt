package net.qrator.failoverbeacon.okhttp.fakes

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** The events of the OkHttp version on the test classpath. */
val eventListenerEvents: List<Method> = EventListener::class.java.declaredMethods
    .filter { Modifier.isPublic(it.modifiers) && !Modifier.isFinal(it.modifiers) }
    .filter { it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers) }
    .sortedBy { it.name }

/** Calls [event] on [listener] with test arguments and rethrows what the listener throws. */
fun sendEvent(listener: EventListener, event: Method) {
    val arguments = event.parameterTypes.map { argumentOf(it) }.toTypedArray()
    try {
        event.invoke(listener, *arguments)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}

private val request = Request.Builder().url("http://example.test/").build()

private fun argumentOf(type: Class<*>): Any? = when (type) {
    Call::class.java -> OkHttpClient().newCall(request)

    Request::class.java -> request

    Response::class.java -> Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()

    HttpUrl::class.java -> request.url

    Dispatcher::class.java -> Dispatcher()

    Connection::class.java -> java.lang.reflect.Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java)
    ) { _, _, _ -> null }

    Proxy::class.java -> Proxy.NO_PROXY

    InetSocketAddress::class.java -> InetSocketAddress.createUnresolved("example.test", 80)

    Protocol::class.java -> Protocol.HTTP_1_1

    IOException::class.java -> IOException("test")

    String::class.java -> "example.test"

    List::class.java -> emptyList<Any>()

    java.lang.Long.TYPE -> 0L

    java.lang.Boolean.TYPE -> false

    else -> null
}
