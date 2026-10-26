package com.folio.reader.narration

import kotlinx.datetime.*

data class ListeningSlice(val startedAt: Instant, val durationMs: Long, val date: LocalDate)

/** Split measured elapsed time at civil day boundaries without crediting a clock jump. */
fun splitListeningInterval(end: Instant, durationMs: Long, zone: TimeZone): List<ListeningSlice> {
    val finish = end.toEpochMilliseconds()
    var cursor = finish-durationMs.coerceAtLeast(0)
    return buildList {
        while (cursor < finish) {
            val date = Instant.fromEpochMilliseconds(cursor).toLocalDateTime(zone).date
            val midnight = date.plus(DatePeriod(days=1)).atStartOfDayIn(zone).toEpochMilliseconds()
            val boundary = minOf(finish,midnight.coerceAtLeast(cursor+1))
            add(ListeningSlice(Instant.fromEpochMilliseconds(cursor),boundary-cursor,date))
            cursor = boundary
        }
    }
}
