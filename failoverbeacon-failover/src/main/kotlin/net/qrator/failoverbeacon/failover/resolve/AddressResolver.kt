package net.qrator.failoverbeacon.failover.resolve

import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.NetworkSession
import net.qrator.failoverbeacon.failover.network.NetworkSessions

internal class AddressResolver(
    private val sessions: NetworkSessions,
    private val tasks: ResolveTasks,
    private val timer: RefreshTimer,
    private val preresolveOnNetworkChange: Boolean,
    private val waitMillis: Long
) {
    fun preresolve() {
        val session = sessions.current ?: return
        if (preresolveOnNetworkChange || session.isFirstNetwork) {
            session.states
                .filterNot { it.dns.isFresh(timer) }
                .forEach { tasks.start(session, it.config.host) }
        }
    }

    fun resolveStaleHosts() {
        val session = sessions.current ?: return
        session.states
            .filter { isResolveAllowed(session, it) && it.dns.isStale(timer) }
            .forEach { tasks.start(session, it.config.host) }
    }

    fun resolveInBackground(host: String) {
        val session = sessions.current ?: return
        tasks.start(session, host)
    }

    fun resolveAndWait(host: String) {
        val session = sessions.current ?: return
        val done = tasks.start(session, host)
        try {
            if (!done.await(waitMillis, TimeUnit.MILLISECONDS)) {
                session.saveResolveFailure(host)
                SdkRegistry.logger.debug { "[Failover] $host: DNS wait timed out" }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isResolveAllowed(session: NetworkSession, state: HostState): Boolean =
        preresolveOnNetworkChange || session.isFirstNetwork || state.dns.hasAnswer
}
