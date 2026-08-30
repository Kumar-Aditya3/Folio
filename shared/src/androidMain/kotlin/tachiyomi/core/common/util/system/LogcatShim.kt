package tachiyomi.core.common.util.system

import android.util.Log
import logcat.LogPriority

/**
 * Replacement for Mihon's logcat wrapper, writing through to android.util.Log under the
 * same FQCN so vendored sources compile unchanged.
 */
fun logcat(
    priority: LogPriority = LogPriority.DEBUG,
    throwable: Throwable? = null,
    message: () -> String = { "" },
) {
    val text = message()
    val rendered = if (throwable != null) "$text\n${Log.getStackTraceString(throwable)}" else text
    Log.println(priority.value, "FolioManga", rendered)
}
