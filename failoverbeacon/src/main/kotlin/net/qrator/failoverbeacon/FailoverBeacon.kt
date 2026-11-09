package net.qrator.failoverbeacon

import android.content.Context
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.TrackingConsent
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.logging.info
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.core.util.catchSdkFailure
import net.qrator.failoverbeacon.internal.AndroidMainProcessDetector
import net.qrator.failoverbeacon.internal.LogcatLogger
import net.qrator.failoverbeacon.internal.SdkStartup

/**
 * Entry point of the FailoverBeacon SDK. Until [initialize], the SDK captures nothing. All
 * methods are thread-safe.
 */
public object FailoverBeacon {
    init {
        SdkRegistry.logger.output = LogcatLogger
    }

    /**
     * Starts the SDK. Call it from `Application.onCreate`; it does no disk or network I/O on the
     * calling thread. Failover hosts from [FailoverBeaconConfig] are resolved and checked in the
     * background right away. Apps with a consent banner pass [Consent.PENDING] as [consent] until
     * the user decides. The default value is [Consent.GRANTED].
     *
     * Works only in the app's main process. A repeated call is ignored.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(
        context: Context,
        config: FailoverBeaconConfig,
        consent: Consent = Consent.GRANTED
    ) {
        if (SdkRegistry.instance != null) {
            SdkRegistry.logger.warn { ALREADY_INITIALIZED }
            return
        }
        val appContext = context.applicationContext ?: context
        val processDetector = AndroidMainProcessDetector(appContext)
        if (!processDetector.isMainProcess()) {
            SdkRegistry.logger.warn { "[SDK] Not in the main process. SDK is inactive" }
            return
        }
        var started: Boolean? = null
        catchSdkFailure(
            onFailure = {
                SdkRegistry.logger.error(it) { "[SDK] Cannot start the SDK. SDK is inactive" }
            }
        ) {
            started = SdkStartup.start(appContext, toTrackingConsent(consent), config)
        }
        when (started) {
            null -> return
            false -> SdkRegistry.logger.warn { ALREADY_INITIALIZED }
            true -> SdkRegistry.logger.info { "[SDK] Initialized" }
        }
    }

    /**
     * Changes the user's [consent]. The SDK does not persist it. [Consent.NOT_GRANTED] deletes the
     * stored events. Ignored while the SDK is not running.
     */
    @JvmStatic
    public fun setConsent(consent: Consent) {
        val instance = SdkRegistry.instance
        if (instance == null) {
            SdkRegistry.logger.warn { "[SDK] Not running. setConsent ignored" }
            return
        }
        instance.applyConsent(toTrackingConsent(consent))
    }

    /**
     * Deletes stored events and saved failover state without changing consent. Ignored while the
     * SDK is not running.
     */
    @JvmStatic
    public fun clearAllData() {
        val instance = SdkRegistry.instance
        if (instance == null) {
            SdkRegistry.logger.warn { "[SDK] Not running. clearAllData ignored" }
            return
        }
        instance.clearData()
    }

    /**
     * Stops the SDK until the next [initialize]: calls are no longer recorded and the SDK stops
     * watching the network and the app's activities. Stored data is kept. Ignored while the SDK
     * is not running.
     */
    @JvmStatic
    public fun stop() {
        if (SdkRegistry.instance == null) {
            SdkRegistry.logger.warn { "[SDK] Not running. stop ignored" }
            return
        }
        SdkRegistry.clear()
    }

    /**
     * Sets the minimum priority of SDK log messages, one of the [android.util.Log] constants from
     * [android.util.Log.VERBOSE] to [android.util.Log.ASSERT]. The default value is
     * [android.util.Log.WARN]. Works before [initialize].
     *
     * @throws IllegalArgumentException if [level] is not one of these constants.
     */
    @JvmStatic
    public fun setLogLevel(level: Int) {
        SdkRegistry.logger.minLevel = level
    }

    /**
     * Sends SDK log messages to [logger] instead of Logcat; null disables logging. Exceptions
     * thrown by [logger] are ignored. Works before [initialize].
     */
    @JvmStatic
    public fun setLogger(logger: FailoverBeaconLogger?) {
        SdkRegistry.logger.output = logger?.let { LogOutput(it::log) }
    }

    private fun toTrackingConsent(consent: Consent): TrackingConsent = when (consent) {
        Consent.GRANTED -> TrackingConsent.GRANTED
        Consent.PENDING -> TrackingConsent.PENDING
        Consent.NOT_GRANTED -> TrackingConsent.NOT_GRANTED
    }

    private const val ALREADY_INITIALIZED = "[SDK] Already initialized. Ignored"
}
