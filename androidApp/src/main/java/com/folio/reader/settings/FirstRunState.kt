package com.folio.reader.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class FirstRunState(context: Context, preferenceName: String = "folio_setup",
    freshInstallation: Boolean = isFreshInstallation(context)) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    var pending by mutableStateOf(needsFirstRunSetup(
        preferences.getBoolean("completed", false), preferences.getBoolean("introduced", false),
        preferences.getBoolean("started", false), freshInstallation))
        private set
    var step by mutableStateOf(FirstRunStep.entries.getOrElse(preferences.getInt("step", 0)) { FirstRunStep.Welcome })
        private set

    init {
        if (pending) preferences.edit().putBoolean("started", true).apply()
        else preferences.edit().putBoolean("completed", true).apply()
    }

    fun moveTo(next: FirstRunStep) {
        step = next
        preferences.edit().putInt("step", next.ordinal).apply()
    }
    fun finish() {
        preferences.edit().putBoolean("completed", true).putBoolean("introduced", true).apply()
        pending = false
    }
}

@Suppress("DEPRECATION")
private fun isFreshInstallation(context: Context): Boolean {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return info.firstInstallTime == info.lastUpdateTime
}
