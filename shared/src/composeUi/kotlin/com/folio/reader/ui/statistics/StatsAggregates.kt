package com.folio.reader.ui.statistics

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** Consecutive reading days ending today — or yesterday, if today hasn't started yet. */
internal fun currentStreak(readDays: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in readDays) today else today.minus(DatePeriod(days = 1))
    var streak = 0
    while (day in readDays) {
        streak++
        day = day.minus(DatePeriod(days = 1))
    }
    return streak
}
