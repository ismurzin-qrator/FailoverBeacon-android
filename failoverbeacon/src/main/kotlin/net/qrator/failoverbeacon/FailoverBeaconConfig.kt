package net.qrator.failoverbeacon

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * SDK configuration passed to `FailoverBeacon.initialize`.
 *
 * Instances are immutable; create them with [Builder]:
 * ```
 * val config = FailoverBeaconConfig.Builder()
 *     .beacons(listOf("https://beacon.example"))
 *     .build()
 * ```
 */
public class FailoverBeaconConfig private constructor(
    internal val beacons: List<String>,
    internal val blockedHosts: Set<String>,
    internal val failureSampleRate: Double,
    internal val successSampleRate: Double,
    internal val options: FailoverBeaconOptions
) {
    /** Builds [FailoverBeaconConfig]. [beacons] is required; other setters are optional. */
    public class Builder {
        private var beacons: List<String> = emptyList()
        private var blockedHosts: Set<String> = emptySet()
        private var failureSampleRate = DEFAULT_FAILURE_SAMPLE_RATE
        private var successSampleRate = DEFAULT_SUCCESS_SAMPLE_RATE
        private var options: FailoverBeaconOptions? = null

        /**
         * Sets the beacon URLs that receive telemetry, for example
         * `listOf("https://beacon.example/reports")`. Each beacon receives every event
         * independently of the others. Duplicate URLs are ignored.
         *
         * @throws IllegalArgumentException if [beacons] is empty or contains a URL that is not an
         *     `https://` URL with a host.
         */
        public fun beacons(beacons: List<String>): Builder = apply {
            require(beacons.isNotEmpty()) { "beacons must not be empty" }
            beacons.forEach(::checkBeaconUrl)
            this.beacons = beacons.distinct()
        }

        /**
         * Sets hosts whose requests are never reported, for example
         * `listOf("analytics.example")`. Names match exactly and ignore case; subdomains don't
         * match. Pass host names without a port: `"analytics.example:443"` never matches.
         * By default, no hosts are blocked.
         *
         * @throws IllegalArgumentException if a host is blank, is a URL or is not a valid name.
         */
        public fun blockedHosts(hosts: List<String>): Builder = apply {
            blockedHosts = hosts.mapTo(LinkedHashSet(), ::toAsciiHost)
        }

        /**
         * Sets the fraction of failed requests that are reported. Each failed request is reported
         * with this probability: 1.0 reports all of them, 0.25 about one in four, 0.0 none. The
         * default value is 1.0.
         *
         * @throws IllegalArgumentException if [rate] is outside [0.0, 1.0] or is NaN.
         */
        public fun failureSampleRate(rate: Double): Builder = apply {
            failureSampleRate = checkSampleRate("failureSampleRate", rate)
        }

        /**
         * Sets the fraction of successful requests that are reported. Each successful request is
         * reported with this probability: 1.0 reports all of them, 0.1 about one in ten, 0.0 none.
         * Successes let the beacon compute the failure rate. The default value is 0.1.
         *
         * @throws IllegalArgumentException if [rate] is outside [0.0, 1.0] or is NaN.
         */
        public fun successSampleRate(rate: Double): Builder = apply {
            successSampleRate = checkSampleRate("successSampleRate", rate)
        }

        /** Sets additional options. By default, every option has its default value. */
        public fun options(options: FailoverBeaconOptions): Builder = apply {
            this.options = options
        }

        /**
         * Creates the configuration.
         *
         * @throws IllegalArgumentException if no beacons are configured.
         */
        public fun build(): FailoverBeaconConfig {
            require(beacons.isNotEmpty()) { "beacons are required" }
            return FailoverBeaconConfig(
                beacons = beacons,
                blockedHosts = blockedHosts,
                failureSampleRate = failureSampleRate,
                successSampleRate = successSampleRate,
                options = options ?: FailoverBeaconOptions.Builder().build()
            )
        }

        private companion object {
            private const val DEFAULT_FAILURE_SAMPLE_RATE = 1.0
            private const val DEFAULT_SUCCESS_SAMPLE_RATE = 0.1

            private fun checkBeaconUrl(url: String) {
                val uri = try {
                    URI(url)
                } catch (e: URISyntaxException) {
                    throw IllegalArgumentException("Invalid beacon URL: $url", e)
                }
                require(uri.scheme.equals("https", ignoreCase = true)) {
                    "Beacon URL must use https: $url"
                }
                require(!uri.host.isNullOrEmpty()) { "Beacon URL must have a host: $url" }
            }

            private fun toAsciiHost(host: String): String {
                require(host.isNotBlank() && host.none { it == '/' || it.isWhitespace() }) {
                    "blockedHosts must contain host names, was \"$host\""
                }
                return IDN.toASCII(host).lowercase(Locale.ROOT)
            }

            private fun checkSampleRate(name: String, rate: Double): Double {
                require(rate in 0.0..1.0) { "$name must be in [0.0, 1.0], was $rate" }
                return rate
            }
        }
    }
}
