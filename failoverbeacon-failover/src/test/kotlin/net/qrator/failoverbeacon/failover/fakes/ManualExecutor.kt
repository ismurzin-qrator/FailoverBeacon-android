package net.qrator.failoverbeacon.failover.fakes

import java.util.concurrent.Executor

class ManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    val queued: Int get() = tasks.size

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }
}
