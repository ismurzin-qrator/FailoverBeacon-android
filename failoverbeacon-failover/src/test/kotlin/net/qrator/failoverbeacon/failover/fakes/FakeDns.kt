package net.qrator.failoverbeacon.failover.fakes

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FakeDns {
    private val answers = ConcurrentHashMap<String, () -> List<InetAddress>>()
    val lookups = CopyOnWriteArrayList<String>()

    fun answer(host: String, answer: () -> List<InetAddress>) {
        answers[host] = answer
    }

    fun answer(host: String, vararg addresses: InetAddress) = answer(host) { addresses.toList() }

    fun fail(host: String) = answer(host) { throw UnknownHostException(host) }

    fun lookup(host: String): List<InetAddress> {
        lookups += host
        val answer = answers[host] ?: throw UnknownHostException(host)
        return answer()
    }
}

fun ip(last: Int): InetAddress =
    InetAddress.getByAddress("api.example", byteArrayOf(192.toByte(), 0, 2, last.toByte()))
