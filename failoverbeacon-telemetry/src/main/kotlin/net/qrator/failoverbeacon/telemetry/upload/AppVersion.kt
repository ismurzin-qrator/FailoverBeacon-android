package net.qrator.failoverbeacon.telemetry.upload

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal fun Context.appVersionName(): String? {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        // PackageInfoFlags exist from API 33.
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    return info.versionName?.takeIf { it.isNotEmpty() }
}
