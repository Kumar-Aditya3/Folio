package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.content.pm.PackageManager

fun Context.isPackageInstalled(packageName: String): Boolean {
    return try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
