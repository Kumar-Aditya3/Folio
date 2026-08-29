package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** The device's own clock, formatted the way the operating system formats it. */
@Composable
expect fun rememberClockTime(): String

/**
 * Flips once a minute, exactly on the minute.
 *
 * Sleeping to the next boundary rather than polling on a fixed interval means the
 * reading can never drift away from the wall clock, which is what a periodic
 * "recompute the string" loop does.
 */
@Composable
internal fun rememberMinuteTick(): Long {
    var minute by remember { mutableStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            minute = System.currentTimeMillis() / 60_000L
        }
    }
    return minute
}
