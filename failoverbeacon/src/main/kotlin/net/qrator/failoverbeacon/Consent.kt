package net.qrator.failoverbeacon

/**
 * The user's consent to telemetry.
 *
 * The SDK does not persist consent: the app stores the user's choice and passes it on every
 * launch.
 */
public enum class Consent {
    /** Events are collected and uploaded, including events stored while consent was [PENDING]. */
    GRANTED,

    /** Events are collected and stored on disk, but not uploaded until consent is [GRANTED]. */
    PENDING,

    /** Collection stops and stored events are deleted. */
    NOT_GRANTED
}
