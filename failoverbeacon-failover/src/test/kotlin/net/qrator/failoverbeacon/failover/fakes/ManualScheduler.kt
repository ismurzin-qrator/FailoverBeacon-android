package net.qrator.failoverbeacon.failover.fakes

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ManualScheduler : ScheduledThreadPoolExecutor(1) {
    private val tasks = ArrayDeque<Runnable>()

    val queued: Int get() = tasks.size

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        tasks.addLast(command)
        return super.schedule({}, 0, TimeUnit.MILLISECONDS)
    }
}
