package net.qrator.failoverbeacon.failover.fakes

import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.MaxAge
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.BodyProber
import net.qrator.failoverbeacon.failover.probe.TcpProber

private fun idleSessions() = NetworkSessions(
    KnownNetworks(emptyList(), maxNetworks = 1),
    MaxAge({ 0L }, 1)
)

// No current session: these probers have nothing to probe, for tests of what comes before them.
internal fun idleBodyProber() = BodyProber(
    idleSessions(),
    { _, _, _, _ -> BodyProbeOutcome.PASSED },
    MaxAge({ 0L }, 1),
    ManualExecutor()
)

internal fun idleTcpProber() = TcpProber(
    idleSessions(),
    FakeTcpProbe()::run,
    ManualExecutor(),
    idleBodyProber()
)
