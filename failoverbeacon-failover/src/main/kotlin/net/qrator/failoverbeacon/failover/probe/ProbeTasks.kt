package net.qrator.failoverbeacon.failover.probe

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.ProbeResult
import net.qrator.failoverbeacon.failover.network.NetworkSession

internal class ProbeTasks(
    private val connect: (InetAddress, Int) -> ProbeResult,
    private val executor: Executor
) {
    private val running = ConcurrentHashMap.newKeySet<Probe>()

    fun start(session: NetworkSession, config: FailoverHostConfig, address: InetAddress) {
        val probe = Probe(session, config, address)
        if (running.add(probe)) executor.execute { run(probe) }
    }

    private fun run(probe: Probe) {
        try {
            if (!probe.session.isActive) return
            val result = connect(probe.address, probe.config.port)
            SdkRegistry.logger.debug {
                "[Failover] ${probe.config.host} ${probe.address.hostAddress}: ${describe(result)}"
            }
            probe.session.saveProbe(probe.config.host, probe.address, result)
        } finally {
            running -= probe
        }
    }

    private fun describe(result: ProbeResult): String = when (result) {
        is ProbeResult.Connected -> "${TimeUnit.NANOSECONDS.toMillis(result.connectNanos)} ms"
        ProbeResult.Failed -> "failed"
        ProbeResult.Unknown -> "unknown"
    }

    private data class Probe(
        val session: NetworkSession,
        val config: FailoverHostConfig,
        val address: InetAddress
    )
}
