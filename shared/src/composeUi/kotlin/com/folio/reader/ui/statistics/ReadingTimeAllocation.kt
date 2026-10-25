package com.folio.reader.ui.statistics

import com.folio.reader.manga.MangaEntry
import com.folio.reader.model.Book
import com.folio.reader.model.ReadingSession

/** Identity and time from the same filtered session window, for either reading format. */
data class ReadingTimeEntry(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val minutes: Long,
    val isManga: Boolean = false,
    val sourceId: Long? = null,
    val thumbnailUrl: String? = null,
)

internal fun readingTimeEntries(
    sessions: List<ReadingSession>, books: List<Book>, manga: List<MangaEntry>,
): List<ReadingTimeEntry> {
    val bookMap = books.associateBy { it.id }
    // Resolved extension subjects can be present for other statistics. Allocation
    // belongs to the current library; an unfavourited read never becomes a row or remainder.
    val mangaMap = manga.filter { it.inLibrary }.associateBy { it.id }
    // The same string id can exist in both libraries. Keep format in the grouping key.
    return sessions.groupBy { it.isMangaSession() to it.bookId }.mapNotNull { (key, group) ->
        val minutes = group.sumOf { it.durationMs } / 60_000
        if (minutes <= 0L) return@mapNotNull null
        val (isManga, id) = key
        if (isManga) mangaMap[id]?.let {
            ReadingTimeEntry(id, it.title, it.author.orEmpty(), it.coverPath, minutes, true, it.sourceId, it.thumbnailUrl)
        } else bookMap[id]?.let {
            ReadingTimeEntry(id, it.displayTitle, it.displayAuthor, it.coverPath, minutes)
        }
    }.sortedWith(compareByDescending<ReadingTimeEntry> { it.minutes }.thenBy { it.isManga }.thenBy { it.id })
}

internal fun readingTimeRemainder(rows: List<ReadingTimeEntry>, totalMinutes: Long): Long =
    (totalMinutes - rows.sumOf { it.minutes }).coerceAtLeast(0L)
