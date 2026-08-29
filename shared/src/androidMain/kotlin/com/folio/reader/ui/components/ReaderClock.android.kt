package com.folio.reader.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Date

/**
 * Reads the system clock through the same formatter the status bar uses, so the
 * reader shows the time in the user's own 12/24-hour preference rather than a
 * hand-built string.
 */
@Composable
actual fun rememberClockTime(): String {
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }
    val tick = rememberMinuteTick()
    return remember(tick, formatter) { formatter.format(Date()) }
}
