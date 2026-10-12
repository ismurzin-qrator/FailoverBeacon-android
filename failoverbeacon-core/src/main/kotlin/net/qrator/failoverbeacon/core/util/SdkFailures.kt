package net.qrator.failoverbeacon.core.util

/** Runs [block], [Exception] and [LinkageError] goes to [onFailure], other errors propagate. */
inline fun catchSdkFailure(onFailure: (Throwable) -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        onFailure(e)
    } catch (e: LinkageError) {
        onFailure(e)
    }
}
