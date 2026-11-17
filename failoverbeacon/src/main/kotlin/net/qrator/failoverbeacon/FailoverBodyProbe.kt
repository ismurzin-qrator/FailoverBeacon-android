package net.qrator.failoverbeacon

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
    path: String = "/",
    maxBytes: Int = DEFAULT_MAX_BYTES
) {
    internal val path: String = path.also { value ->
        val isValid = value.startsWith("/") && value.all { it in '!'..'~' && it != '#' }
        require(isValid) {
            "path must start with / and have only visible ASCII, without a fragment, was \"$value\""
        }
    }

    internal val maxBytes: Int = maxBytes.also {
        require(it in MIN_MAX_BYTES..MAX_MAX_BYTES) {
            "maxBytes must be in $MIN_MAX_BYTES..$MAX_MAX_BYTES, was $it"
        }
    }

    private companion object {
        private const val DEFAULT_MAX_BYTES = 16 * 1024
        private const val MIN_MAX_BYTES = 1024
        private const val MAX_MAX_BYTES = 1024 * 1024
    }
}
