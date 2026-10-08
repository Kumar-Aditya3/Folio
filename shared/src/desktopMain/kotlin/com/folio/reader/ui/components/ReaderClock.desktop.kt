package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The system clock, in the locale's short time format — the same 12/24-hour choice the OS taskbar uses. */
@Composable
actual fun rememberClockTime(): String {
    val tick = rememberMinuteTick()
    return remember(tick) {
        LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
}
