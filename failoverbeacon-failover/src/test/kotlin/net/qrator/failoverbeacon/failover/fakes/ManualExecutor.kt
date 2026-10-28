package net.qrator.failoverbeacon.failover.fakes

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class ManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()
    var busy = false

    val queued: Int get() = tasks.size

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    override fun execute(command: Runnable) {
        if (busy) throw RejectedExecutionException()
        tasks.addLast(command)
    }
}
