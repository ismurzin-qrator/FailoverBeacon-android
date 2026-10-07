package net.qrator.failoverbeacon.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import net.qrator.failoverbeacon.core.logging.InternalLogger
import net.qrator.failoverbeacon.core.logging.debug

/**
 * Tells whether the current process is the app's main one: the process named by
 * `android:process` on `<application>`, the package name by default. An unknown process name
 * counts as the main process.
 */
internal class AndroidMainProcessDetector(
    private val context: Context,
    private val logger: InternalLogger
) {
    fun isMainProcess(): Boolean {
        // An isolated process has its own UID and cannot query ActivityManager below API 28.
        if (Process.myUid() != context.applicationInfo.uid) return false
        val current = currentProcessName()
        if (current == null) {
            logger.debug { "Process name is unknown. Assuming the main process" }
            return true
        }
        return current == context.applicationInfo.processName
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return try {
            val pid = Process.myPid()
            context.getSystemService(ActivityManager::class.java)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        } catch (e: Exception) {
            logger.debug(e) { "Cannot read running app processes" }
            null
        }
    }
}
