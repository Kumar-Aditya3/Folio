package com.folio.reader.ui.statistics

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.QuoteRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.model.Book
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.statistics.StatsScope
import com.folio.reader.ui.components.currentStreak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/** A single day of reading, used by both the week chart and the activity heatmap. */
data class StatDay(val date: LocalDate, val minutes: Long)

/** A book still being read, reduced to what the list needs to show. */
data class ReadingInProgress(
    val id: String,
    val title: String,
    val author: String,
    val progress: Float,
    val finishEstimate: String? = null,
    val coverPath: String? = null
)

/** A recent quote/highlight for the floating quotes card on the stats tab. */
data class RecentQuote(
    val id: String,
    val text: String,
    val bookTitle: String,
    val bookId: String,
    /** Epoch-ms timestamp used to sort the merged feed; 0 when unknown. */
    val sortKey: Long = 0L
)

/** §12.5 top-books leaderboard row: reading time inside the stats window. */
data class TopBook(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val minutes: Long
)

/** §12.5 genre-breakdown row: window minutes per tag. */
data class TagSlice(
    val label: String,
    val minutes: Long
)

/**
 * Every number the statistics screen renders, computed in one pass.
 *
 * The window is deliberately bounded by [StatisticsViewModel.historyDays]: streaks,
 * heatmaps and averages all need a fixed span to be comparable, and an unbounded
 * "all time" query grows with the library for no visible benefit.
 */
data class StatisticsUiState(
    val hasData: Boolean = false,
    val timeThisWeekMs: Long = 0,
    val timeThisYearMs: Long = 0,
    val streakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val activeDaysThisWeek: Int = 0,
    val sessionsThisWeek: Int = 0,
    val wordsReadThisYear: Long = 0,
    val averageSessionMinutes: Double = 0.0,
    val averageSpeedWpm: Double = 0.0,
    val mostReadDay: String = "",
    val mostReadHour: String = "",
    /** Personality headline, e.g. "The Night Owl"; blank until there is data. */
    val chronotype: String = "",
    /** Busiest rolling three-hour window, e.g. "11 PM–2 AM"; blank until there is data. */
    val peakWindow: String = "",
    val booksFinished: Int = 0,
    val booksInProgress: Int = 0,
    val sourceDevices: Int = 0,
    val week: List<StatDay> = emptyList(),
    val heatmap: List<StatDay> = emptyList(),
    /** Heatmap built from book-only sessions (contentLocator not starting with "manga-page"). */
    val heatmapBooks: List<StatDay> = emptyList(),
    /** Heatmap built from manga-only sessions (contentLocator starting with "manga-page"). */
    val heatmapManga: List<StatDay> = emptyList(),
    val currentlyReading: List<ReadingInProgress> = emptyList(),
    /** §12.5 top-books leaderboard: books ranked by window reading minutes. */
    val topBooks: List<TopBook> = emptyList(),
    /** §12.5 genre breakdown: window minutes per tag, largest first. */
    val genres: List<TagSlice> = emptyList(),
    /** Today's reading minutes, derived from sessions whose local date matches today. */
    val todayMinutes: Long = 0
)

class StatisticsViewModel(
    private val bookRepository: BookRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val quoteRepository: QuoteRepository? = null,
    private val highlightRepository: HighlightRepository? = null,
    // §11.2 stats exclusions. Desktop passes none and keeps today's numbers
    // byte-identical; Android also wires the group repos needed to resolve each
    // book's tags and collections (series and status resolve from the books
    // themselves). The settings UI is a later slice.
    private val statsExclusionRepository: StatsExclusionRepository? = null,
    private val tagRepository: TagRepository? = null,
    private val collectionRepository: CollectionRepository? = null
) {
    companion object {
        /** Days of history pulled into every calculation. */
        const val historyDays = 365
        /** Weeks of activity drawn in the heatmap. */
        const val heatmapWeeks = 18
        private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        /** Maximum entries shown in the floating quotes/highlights card. */
        private const val FLOATING_FEED_CAP = 6
        /** §12.5 leaderboard and genre-breakdown caps. */
        private const val TOP_BOOKS_CAP = 5
        private const val GENRE_CAP = 6
    }

    private val timeZone: TimeZone get() = TimeZone.currentSystemDefault()
    private fun today(): LocalDate = Clock.System.todayIn(timeZone)

    /**
     * Reading sessions are synced across devices, so the history here is the whole
     * account's, not this handset's — which is the point of showing it.
     *
     * §11.2: when a [StatsExclusionRepository] is wired, the same [StatsScope]
     * predicate filters both the sessions and the book lists before any number
     * is computed — one question, answered in exactly one place. Without it the
     * legacy pipeline runs untouched.
     */
    val state: Flow<StatisticsUiState> = if (statsExclusionRepository == null) {
        combine(
            sessionRepository.observeSessionsSince(
                today().minus(DatePeriod(days = historyDays)).atStartOfDayIn(timeZone)
            ),
            bookRepository.getCurrentlyReading(),
            bookRepository.getFinishedBooks(),
            // §12.5 top books / genres resolve titles, covers and tags against
            // the whole library, not just the reading/finished subsets.
            bookRepository.getAllBooks()
        ) { sessions, inProgress, finished, books ->
            buildState(sessions, inProgress, finished, books)
        }
    } else {
        combine(
            sessionRepository.observeSessionsSince(
                today().minus(DatePeriod(days = historyDays)).atStartOfDayIn(timeZone)
            ),
            bookRepository.getCurrentlyReading(),
            bookRepository.getFinishedBooks(),
            // Exclusions can hit books absent from both lists above (abandoned,
            // unread, deleted) — resolve against the whole library.
            bookRepository.getAllBooks(),
            statsExclusionRepository.observeExclusions()
        ) { sessions, inProgress, finished, allBooks, exclusions ->
            val scope = StatsScope(exclusions)
            val excluded = excludedBookIds(allBooks, scope)
            // Direct BOOK rows also cover sessions whose book row is gone
            // (deleted) — there are no groups left to resolve for those.
            val directBooks = exclusions.mapNotNull { (kind, id) ->
                if (kind == Scope.BOOK) id else null
            }.toSet()
            buildState(
                sessions.filterNot { it.bookId in excluded || it.bookId in directBooks },
                inProgress.filterNot { it.id in excluded },
                finished.filterNot { it.id in excluded },
                allBooks
            )
        }
    }

    /**
     * §11.2/Rule 8: the live exclusion set, for hosts that must react to it —
     * the "Some titles are excluded — review" line and the manga stats pass it
     * straight into [com.folio.reader.manga.MangaStatisticsRepository.getStatistics].
     */
    val exclusions: Flow<Set<Pair<Scope, String>>> =
        statsExclusionRepository?.observeExclusions() ?: flowOf(emptySet())

    /**
     * §11.2 one-way resolution, evaluated once per emission: a book is excluded
     * when [StatsScope.includesBook] says so — listed directly, or any of its
     * tags/collections, its series or its status is listed. Each book's groups
     * are resolved exactly once per pass and reused for the session filter and
     * both book lists; series membership needs no lookup because every book
     * carries its own seriesId, so excluding a series removes all of its books.
     */
    private suspend fun excludedBookIds(books: List<Book>, scope: StatsScope): Set<String> {
        val excluded = HashSet<String>()
        val tagCache = HashMap<String, Set<String>>()
        val collectionCache = HashMap<String, Set<String>>()
        for (book in books) {
            val tagIds = tagCache.getOrPut(book.id) {
                tagRepository?.getTagsForBook(book.id)?.map { it.id }?.toSet().orEmpty()
            }
            val collectionIds = collectionCache.getOrPut(book.id) {
                collectionRepository?.getCollectionsForBook(book.id)?.map { it.id }?.toSet().orEmpty()
            }
            if (!scope.includesBook(book.id, tagIds, collectionIds, book.seriesId, book.status)) {
                excluded.add(book.id)
            }
        }
        return excluded
    }

    /**
     * Merged feed of saved quotes and user highlights for the floating-quotes card.
     * Quotes carry their own createdAt timestamp; highlights are ordered by their
     * createdAt when available, with books' lastOpenedAt as a fallback recency signal.
     * The merged list is sorted newest-first and capped at [FLOATING_FEED_CAP].
     * Null only when neither repository was provided.
     */
    val recentQuotes: Flow<List<RecentQuote>>? = buildRecentFeed()

    private fun buildRecentFeed(): Flow<List<RecentQuote>>? {
        if (quoteRepository == null && highlightRepository == null) return null

        // We combine up to three sources: all quotes, all books (to resolve titles
        // and recency), and per-book highlights gathered on demand.
        val quotesFlow: Flow<List<com.folio.reader.model.Quote>> =
            quoteRepository?.getAllQuotes() ?: flowOf(emptyList())
        val booksFlow: Flow<List<Book>> = bookRepository.getAllBooks()

        return combine(quotesFlow, booksFlow) { quotes, books ->
            val bookMap = books.associateBy { it.id }
            val entries = ArrayList<RecentQuote>(quotes.size + 8)

            // --- Saved quotes ---
            for (q in quotes) {
                val book = bookMap[q.bookId] ?: continue
                entries.add(
                    RecentQuote(
                        id = q.id,
                        text = q.text,
                        bookTitle = book.displayTitle,
                        bookId = q.bookId,
                        sortKey = q.createdAt.toEpochMilliseconds()
                    )
                )
            }

            // --- Highlights from recently-opened books ---
            if (highlightRepository != null) {
                // Sort books by lastOpenedAt descending so we pull highlights from
                // the most-recently-read books first. Books never opened sort last.
                val booksByRecency = books.sortedByDescending {
                    it.lastOpenedAt?.toEpochMilliseconds() ?: 0L
                }
                var highlightBudget = FLOATING_FEED_CAP  // generous upper bound
                for (book in booksByRecency) {
                    if (highlightBudget <= 0) break
                    val highlights = try {
                        highlightRepository.getHighlightsForBook(book.id).first()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    for (h in highlights.filterNot { it.isDeleted }) {
                        if (highlightBudget <= 0) break
                        val trimmed = h.selectedText.trim()
                        if (trimmed.isEmpty()) continue
                        entries.add(
                            RecentQuote(
                                id = h.id,
                                text = trimmed,
                                bookTitle = book.displayTitle,
                                bookId = book.id,
                                sortKey = h.createdAt.toEpochMilliseconds()
                            )
                        )
                        highlightBudget--
                    }
                }
            }

            // Sort newest-first, cap the feed.
            entries.sortByDescending { it.sortKey }
            entries.take(FLOATING_FEED_CAP).map { it.copy(text = capText(it.text)) }
        }
    }

    /** Trim long passages so the card stays readable. */
    private fun capText(text: String, maxChars: Int = 280): String =
        if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "\u2026"

    private suspend fun buildState(
        sessions: List<ReadingSession>,
        inProgress: List<Book>,
        finished: List<Book>,
        books: List<Book> = emptyList()
    ): StatisticsUiState {
        val today = today()
        val weekStart = today.minus(DatePeriod(days = 6))
        val yearStart = today.minus(DatePeriod(days = historyDays - 1))

        val minutesByDay = sessions
            .groupBy { it.startedAt.toLocalDateTime(timeZone).date }
            .mapValues { (_, group) -> group.sumOf { it.durationMs } / 60_000 }

        // Split sessions into book vs manga by the contentLocator discriminator.
        // Manga reader writes sessions with startPosition.contentLocator starting with "manga-page".
        val (mangaSessions, bookSessions) = sessions.partition {
            it.startPosition.contentLocator.startsWith("manga-page")
        }
        val bookMinutesByDay = bookSessions
            .groupBy { it.startedAt.toLocalDateTime(timeZone).date }
            .mapValues { (_, group) -> group.sumOf { it.durationMs } / 60_000 }
        val mangaMinutesByDay = mangaSessions
            .groupBy { it.startedAt.toLocalDateTime(timeZone).date }
            .mapValues { (_, group) -> group.sumOf { it.durationMs } / 60_000 }

        // A day counts as read if it holds a session at all. Measured durations can
        // legitimately round to zero on a short sitting, and treating that as "did not
        // read" breaks a streak the reader actually earned.
        val readDays = sessions.mapTo(mutableSetOf()) { it.startedAt.toLocalDateTime(timeZone).date }

        val thisWeek = sessions.filter { it.startedAt.toLocalDateTime(timeZone).date >= weekStart }
        val thisYear = sessions.filter { it.startedAt.toLocalDateTime(timeZone).date >= yearStart }
        val timed = sessions.filter { it.durationMs > 0 }
        val hourTotals = LongArray(24)
        sessions.forEach { hourTotals[it.startedAt.toLocalDateTime(timeZone).hour] += it.durationMs }

        // §12.5 leaderboard + genre breakdown. Minutes per bookId over the
        // window; manga sessions carry a manga id that is absent from the book
        // map and drop out naturally. Sessions arrive exclusion-filtered, so an
        // excluded title can never appear here (Rule 18).
        val bookMap = books.associateBy { it.id }
        val minutesByBook = sessions
            .groupBy { it.bookId }
            .mapValues { (_, group) -> group.sumOf { it.durationMs } / 60_000 }
        val topBooks = minutesByBook
            .filterValues { it > 0L }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(TOP_BOOKS_CAP)
            .mapNotNull { (bookId, minutes) ->
                bookMap[bookId]?.let { book ->
                    TopBook(book.id, book.displayTitle, book.displayAuthor, book.coverPath, minutes)
                }
            }
        val genres = if (tagRepository == null) {
            emptyList()
        } else {
            val tagNamesByBook = HashMap<String, List<String>>()
            val minutesByTag = LinkedHashMap<String, Long>()
            for ((bookId, minutes) in minutesByBook) {
                if (minutes <= 0L) continue
                val names = tagNamesByBook.getOrPut(bookId) {
                    tagRepository.getTagsForBook(bookId).map { it.name }
                }
                for (name in names) {
                    minutesByTag[name] = (minutesByTag[name] ?: 0L) + minutes
                }
            }
            minutesByTag.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(GENRE_CAP)
                .map { (label, minutes) -> TagSlice(label, minutes) }
        }

        return StatisticsUiState(
            hasData = sessions.isNotEmpty(),
            timeThisWeekMs = thisWeek.sumOf { it.durationMs },
            timeThisYearMs = thisYear.sumOf { it.durationMs },
            streakDays = currentStreak(readDays, today),
            longestStreakDays = longestStreak(readDays),
            activeDaysThisWeek = thisWeek.mapTo(mutableSetOf()) { it.startedAt.toLocalDateTime(timeZone).date }.size,
            sessionsThisWeek = thisWeek.size,
            wordsReadThisYear = thisYear.sumOf { it.wordsRead },
            averageSessionMinutes = if (timed.isEmpty()) 0.0 else timed.sumOf { it.durationMs } / 60_000.0 / timed.size,
            averageSpeedWpm = timed.mapNotNull { it.wordsPerMinute }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            mostReadDay = mostReadDay(minutesByDay),
            mostReadHour = mostReadHour(sessions),
            chronotype = chronotype(hourTotals),
            peakWindow = peakWindow(hourTotals),
            booksFinished = finished.size,
            booksInProgress = inProgress.size,
            sourceDevices = sessions.map { it.deviceId }.distinct().size,
            week = (0..6).map { offset ->
                val day = weekStart.plus(DatePeriod(days = offset))
                StatDay(day, minutesByDay[day] ?: 0L)
            },
            heatmap = heatmapDays(minutesByDay, today),
            heatmapBooks = heatmapDays(bookMinutesByDay, today),
            heatmapManga = heatmapDays(mangaMinutesByDay, today),
            currentlyReading = inProgress.map {
                ReadingInProgress(
                    it.id,
                    it.displayTitle,
                    it.displayAuthor,
                    it.normalizedProgress.toFloat(),
                    com.folio.reader.ui.components.finishEstimate(
                        it.totalWords,
                        it.normalizedProgress,
                        sessions.filter { s -> s.bookId == it.id },
                        sessions
                    )
                )
            },
            topBooks = topBooks,
            genres = genres,
            todayMinutes = minutesByDay[today] ?: 0L
        )
    }

    private fun longestStreak(readDays: Set<LocalDate>): Int {
        val days = readDays.sorted()
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in days) {
            run = if (previous != null && day == previous.plus(DatePeriod(days = 1))) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }
        return longest
    }

    private fun mostReadDay(minutesByDay: Map<LocalDate, Long>): String {
        if (minutesByDay.isEmpty()) return ""
        val totals = LongArray(7)
        minutesByDay.forEach { (day, minutes) -> totals[day.dayOfWeek.ordinal % 7] += minutes }
        val best = totals.indices.maxByOrNull { totals[it] } ?: return ""
        return if (totals[best] == 0L) "" else dayNames[best]
    }

    private fun mostReadHour(sessions: List<ReadingSession>): String {
        val totals = LongArray(24)
        sessions.forEach { totals[it.startedAt.toLocalDateTime(timeZone).hour] += it.durationMs }
        val best = totals.indices.maxByOrNull { totals[it] } ?: return ""
        return if (totals[best] == 0L) "" else hourLabel(best)
    }

    private fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }

    /** Busiest rolling three-hour window of the day, rendered as "11 PM–2 AM". */
    private fun peakWindow(hourTotals: LongArray): String {
        var bestStart = 0
        var bestTotal = 0L
        for (start in 0..23) {
            val total = hourTotals[start] + hourTotals[(start + 1) % 24] + hourTotals[(start + 2) % 24]
            if (total > bestTotal) {
                bestTotal = total
                bestStart = start
            }
        }
        if (bestTotal <= 0L) return ""
        return "${hourLabel(bestStart)}–${hourLabel((bestStart + 3) % 24)}"
    }

    /** Dominant daypart of reading time, phrased as a personality headline. */
    private fun chronotype(hourTotals: LongArray): String {
        val night = (22..23).sumOf { hourTotals[it] } + (0..3).sumOf { hourTotals[it] }
        val morning = (4..11).sumOf { hourTotals[it] }
        val afternoon = (12..17).sumOf { hourTotals[it] }
        val evening = (18..21).sumOf { hourTotals[it] }
        if (night + morning + afternoon + evening == 0L) return ""
        return when (maxOf(night, morning, afternoon, evening)) {
            night -> "The Night Owl"
            morning -> "The Early Bird"
            afternoon -> "The Midday Reader"
            else -> "The Evening Reader"
        }
    }

    /** Monday-aligned weeks so the heatmap's columns read as calendar weeks. */
    private fun heatmapDays(minutesByDay: Map<LocalDate, Long>, today: LocalDate): List<StatDay> {
        val thisMonday = today.minus(DatePeriod(days = today.dayOfWeek.ordinal % 7))
        var day = thisMonday.minus(DatePeriod(days = 7 * (heatmapWeeks - 1)))
        val out = ArrayList<StatDay>(heatmapWeeks * 7)
        while (day <= today) {
            out.add(StatDay(day, minutesByDay[day] ?: 0L))
            day = day.plus(DatePeriod(days = 1))
        }
        return out
    }
}
