package net.qrator.failoverbeacon.failover.fakes

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

class ManualExecutor : Executor {
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    val queued: Int get() = tasks.size

    fun runAll() {
        while (true) tasks.poll()?.run() ?: return
    }

    override fun execute(command: Runnable) {
        tasks += command
    }
}
