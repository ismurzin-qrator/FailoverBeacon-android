package net.qrator.failoverbeacon

import net.qrator.failoverbeacon.core.config.ConfigRules

/**
 * Background HTTPS GET probe that verifies host addresses deliver a response. The probe sends
 * the host name in clear text, so it does not suit hosts whose name is hidden with ECH.
 *
 * [path] request path with optional query, `/` by default.
 * [maxBytes] maximum response bytes to read, 16 KiB by default.
 *
 * @throws IllegalArgumentException if [path] is invalid or [maxBytes] is outside 1 KiB..1 MiB.
 */
public class FailoverBodyProbe @JvmOverloads public constructor(
    path: String = ConfigRules.DEFAULT_BODY_PROBE_PATH,
    maxBytes: Int = ConfigRules.DEFAULT_BODY_PROBE_MAX_BYTES
) {
    internal val path: String = ConfigRules.checkBodyProbePath(path)

    internal val maxBytes: Int = ConfigRules.checkBodyProbeMaxBytes(maxBytes)
}
