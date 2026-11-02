package net.qrator.failoverbeacon.failover.resolve

import java.io.Closeable
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.failover.network.NetworkSession

internal class ResolveTasks(
    private val queryDns: (String) -> List<InetAddress>,
    private val executor: Executor,
    private val onAnswer: (host: String) -> Unit
) : Closeable {
    private val running = ConcurrentHashMap<Task, CountDownLatch>()

    @Volatile
    private var isClosed = false

    fun start(session: NetworkSession, host: String): CountDownLatch {
        if (isClosed) return CountDownLatch(0)
        val task = Task(session, host)
        val done = CountDownLatch(1)
        running.putIfAbsent(task, done)?.let { return it }
        executor.execute { run(task, done) }
        return done
    }

    override fun close() {
        isClosed = true
        running.values.forEach(CountDownLatch::countDown)
    }

    private fun run(task: Task, done: CountDownLatch) {
        var isAnswered = false
        try {
            if (task.session.isActive) isAnswered = queryAndSave(task)
        } finally {
            if (!isAnswered) task.session.saveResolveFailure(task.host)
            running.remove(task)
            done.countDown()
        }
    }

    private fun queryAndSave(task: Task): Boolean = try {
        val addresses = queryDns(task.host)
        SdkRegistry.logger.debug { "[Failover] ${task.host}: ${addresses.size} addresses" }
        if (addresses.isNotEmpty()) {
            task.session.saveAnswer(task.host, addresses)
            onAnswer(task.host)
        }
        addresses.isNotEmpty()
    } catch (e: UnknownHostException) {
        SdkRegistry.logger.debug(e) { "[Failover] ${task.host}: DNS failed" }
        false
    }

    private data class Task(val session: NetworkSession, val host: String)
}
