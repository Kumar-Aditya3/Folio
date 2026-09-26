package com.folio.reader.ui.statistics

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.QuoteRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.statistics.StatsScope
import com.folio.reader.ui.components.currentStreak
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
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

/**
 * Manga sessions are written by the manga reader with a `manga-page…` locator; book
 * sessions carry an EPUB CFI. The discriminator lives here so the split is defined
 * once for the charts, the totals and the local-truth filter below.
 */
internal fun ReadingSession.isMangaSession(): Boolean =
    startPosition.contentLocator.startsWith("manga-page")

/**
 * Sessions whose subject still exists **on this device**.
 *
 * A session outlives its book: deleting the book drops its rows locally, but the
 * next sync-down restores them from the account, and sessions recorded on another
 * device arrive for books that were never here. Either way the numbers stop
 * describing the library in front of the user. Gating on the live book rows is what
 * makes a local delete land in stats straight away, with no sync round in between.
 *
 * Manga sessions pass through: their subject lives in the manga tables, not in
 * [Book], so the book list says nothing about them.
 */
internal fun localSessions(
    sessions: List<ReadingSession>,
    books: List<Book>,
): List<ReadingSession> {
    val localIds = books.mapTo(HashSet(books.size)) { it.id }
    return sessions.filter { session ->
        session.isMangaSession() || session.bookId in localIds
    }
}

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
    val sortKey: Long = 0L,
    /** Spine index of the passage when known, so tapping opens that chapter of the
     *  book rather than wherever the reader was last. Null falls back to opening the book. */
    val spineIndex: Int? = null,
    /** The underlying highlight id when this passage is (or is backed by) a highlight, so a
     *  tap can scroll the reader to the exact mark. Null for a standalone quote. */
    val highlightId: String? = null,
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
    /** Longest single sitting in the window, minutes. */
    val longestSessionMinutes: Long = 0,
    /** Distinct local days with any session this year-window. */
    val daysActiveThisYear: Int = 0,
    /** Books with window reading minutes (N of the "N of M books opened" caption). */
    val booksOpened: Int = 0,
    /** Library size, exclusion-filtered (M of the caption). */
    val librarySize: Int = 0,
    /** Reading minutes bucketed by start hour, 24 slots (the peak-hour band). */
    val hourTotals: List<Long> = List(24) { 0L },
    /** Today's reading minutes, derived from sessions whose local date matches today. */
    val todayMinutes: Long = 0,
    /**
     * The reader's recent pace against their own trailing baseline, or null when the
     * comparison would not be informative. A sentence, not a number — see
     * [com.folio.reader.ui.components.readingTrendSentence] for why the bar for
     * saying anything at all is deliberately high.
     */
    val trendSentence: String? = null
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
    private val collectionRepository: CollectionRepository? = null,
    /**
     * Optional manga backend: when supplied, EXTENSION exclusions are expanded
     * to MANGA_SOURCE rows in [exclusions] so the manga statistics resolve them
     * through the same source-id path everything else uses.
     */
    private val mangaBackend: com.folio.reader.manga.MangaBackend? = null
) {
    companion object {
        /** Days of history pulled into every calculation. */
        const val historyDays = 365
        /**
         * Weeks of activity drawn in the heatmap — a full year, GitHub-style. The
         * history window ([historyDays] = 365) already covers this, so widening the
         * grid costs nothing at the query layer; the grid scrolls horizontally and
         * lands on the most recent week.
         */
        const val heatmapWeeks = 52
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
     * Reading sessions arrive from every device on the account, so the raw history
     * outlives the local library: deleting a book here removes its rows, but the
     * next sync-down hands the same sessions back, and a book that only ever
     * existed on another device never had a row to begin with.
     *
     * Stats answer for the library the user can actually see, so a session only
     * counts while the thing it was read from is still on this device. That makes a
     * local delete take effect immediately — no sync round needed.
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
            // One library query: the reading/finished subsets are strict, order-preserving filters
            // of it (both order by COALESCE(last_opened_at, added_at) DESC). Dropping the separate
            // getCurrentlyReading/getFinishedBooks flows also keeps Statistics off progressRevision,
            // so it no longer re-runs on every reading-progress tick.
            bookRepository.getAllBooks()
        ) { sessions, books ->
            val inProgress = books.filter { it.status == BookStatus.READING || it.status == BookStatus.PAUSED }
            val finished = books.filter { it.status == BookStatus.FINISHED }
            buildState(localSessions(sessions, books), inProgress, finished, books)
        }
    } else {
        combine(
            sessionRepository.observeSessionsSince(
                today().minus(DatePeriod(days = historyDays)).atStartOfDayIn(timeZone)
            ),
            bookRepository.getAllBooks(),
            statsExclusionRepository.observeExclusions()
        ) { sessions, allBooks, exclusions ->
            val inProgress = allBooks.filter { it.status == BookStatus.READING || it.status == BookStatus.PAUSED }
            val finished = allBooks.filter { it.status == BookStatus.FINISHED }
            val scope = StatsScope(exclusions)
            val excluded = excludedBookIds(allBooks, scope)
            // Direct BOOK rows also cover sessions whose book row is gone
            // (deleted) — there are no groups left to resolve for those.
            val directBooks = exclusions.mapNotNull { (kind, id) ->
                if (kind == Scope.BOOK) id else null
            }.toSet()
            buildState(
                localSessions(sessions, allBooks)
                    .filterNot { it.bookId in excluded || it.bookId in directBooks },
                inProgress.filterNot { it.id in excluded },
                finished.filterNot { it.id in excluded },
                // The drill core renders every book it is given — unread ones
                // included, and those carry no sessions for the filter above to
                // catch — so exclusions must land in the book list itself
                // (§11.2/Rule 18). The leaderboard is unaffected: it only ever
                // resolved ids whose sessions already survived.
                allBooks.filterNot { it.id in excluded }
            )
        }
    }

    /**
     * False until [state] has produced its first real emission.
     *
     * [state] is a `combine` of four suspense queries (a year of sessions, the
     * in-progress list, the finished list and the whole library) plus, when
     * exclusions are wired, two roundtrips per book to resolve tags and
     * collections. That is not a fast first value, and `collectAsState(initial =
     * StatisticsUiState())` publishes an *empty* state for the whole of it — so the
     * tab paints zeroed figures and an empty heatmap, then snaps to the real
     * numbers. The reader sees the numbers change under them.
     *
     * Hosts gate on this to render a skeleton of the same shape instead, so the
     * layout is stable and only the values arrive. Mirrors
     * `MangaLibraryViewModel.ready`, which exists for the same reason.
     *
     * Derived from [state] itself rather than a separate flag so it cannot get out
     * of step with the emission it is describing.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ready: Flow<Boolean> = state
        .map { true }
        .onStart { emit(false) }

    /**
     * §11.2/Rule 8: the live exclusion set, for hosts that must react to it —
     * the "Some titles are excluded — review" line and the manga stats pass it
     * straight into [com.folio.reader.manga.MangaStatisticsRepository.getStatistics].
     *
     * When a manga backend is wired, EXTENSION rows are expanded to MANGA_SOURCE
     * rows here — the database only knows numeric source ids, so the extension
     * exclusion a reader set in Settings resolves to every source of that
     * extension before the manga statistics compute with it.
     */
    val exclusions: Flow<Set<Pair<Scope, String>>> =
        statsExclusionRepository?.observeExclusions()?.let { flow ->
            val backend = mangaBackend
            if (backend == null) flow
            else combine(flow, backend.observeSources()) { raw, sources ->
                com.folio.reader.statistics.expandExtensionExclusions(raw, sources)
            }
        } ?: flowOf(emptySet())

    /**
     * [state], [ready] and [exclusions] as process-wide hot flows.
     *
     * The three above are cold, so a host that collects them from inside a
     * composable re-runs the whole query set on every composition — a year of
     * sessions, the in-progress and finished lists, the entire library, and a
     * roundtrip per book to resolve exclusions. In a nav destination that means
     * each visit to the tab paid it again from zero, and the reader watched the
     * skeleton and then the numbers arrive on every return.
     *
     * `Eagerly` so the work starts when the view model is built — at app open, for
     * a host that hoists it — rather than on first access, which is exactly the
     * visit that would otherwise show the skeleton. The underlying queries stay
     * live: the database pushes invalidations through the `combine`, so a hot flow
     * still corrects itself while the tab is on screen. Sharing changes *when* the
     * query runs, never whether it can go stale.
     *
     * Held here rather than in each host so Android and desktop get the same
     * behaviour without either reimplementing it.
     */
    private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sharedState: StateFlow<StatisticsUiState> =
        state.stateIn(sharedScope, SharingStarted.Eagerly, StatisticsUiState())

    /**
     * Derived from [sharedState], **not** from a second `stateIn` of the cold
     * [ready].
     *
     * [ready] is itself `state.map { true }`, so subscribing to both would collect
     * the cold `state` twice and run its entire query set twice per process — a
     * duplicated load introduced the moment two flows share one source. Mapping the
     * already-shared value keeps one subscription and one set of queries, and
     * `sharedState.value.hasData` is the same fact [ready] was expressing.
     */
    val sharedReady: StateFlow<Boolean> =
        sharedState
            .map { it.hasData }
            .stateIn(sharedScope, SharingStarted.Eagerly, false)

    val sharedExclusions: StateFlow<Set<Pair<Scope, String>>> =
        exclusions.stateIn(sharedScope, SharingStarted.Eagerly, emptySet())

    /**
     * §11.2 one-way resolution, evaluated once per emission: a book is excluded
     * when [StatsScope.includesBook] says so — listed directly, or any of its
     * tags/collections, its series or its status is listed. Each book's groups
     * are resolved exactly once per pass and reused for the session filter and
     * both book lists; series membership needs no lookup because every book
     * carries its own seriesId, so excluding a series removes all of its books.
     *
     * The group resolution is two repository roundtrips per book, so it only
     * runs when a BOOK_TAG or BOOK_COLLECTION rule exists to match against —
     * the same guard [HomeViewModel] applies (see `StatsScope.hasRules`).
     */
    private suspend fun excludedBookIds(books: List<Book>, scope: StatsScope): Set<String> {
        val excluded = HashSet<String>()
        val resolveTags = scope.hasRules(Scope.BOOK_TAG)
        val resolveCollections = scope.hasRules(Scope.BOOK_COLLECTION)
        // Batch the group memberships once (a single scan of book_tags / book_collections) instead
        // of two repository round-trips per book, which was the slow step on a large library.
        val tagsByBook: Map<String, Set<String>> = if (resolveTags) {
            val byBook = HashMap<String, MutableSet<String>>()
            tagRepository?.getBookTagLinks()?.forEach { (tagId, bookIds) ->
                bookIds.forEach { b -> byBook.getOrPut(b) { HashSet() }.add(tagId) }
            }
            byBook
        } else {
            emptyMap()
        }
        val collectionsByBook: Map<String, Set<String>> =
            if (resolveCollections) collectionRepository?.getBookCollectionLinks().orEmpty() else emptyMap()
        for (book in books) {
            val tagIds = if (resolveTags) tagsByBook[book.id].orEmpty() else emptySet()
            val collectionIds = if (resolveCollections) collectionsByBook[book.id].orEmpty() else emptySet()
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

    /**
     * [recentQuotes] as a hot flow, for the same reason as [sharedState]: the feed
     * walks every book in the library issuing a highlight query per book, so a cold
     * collection from inside a composable repeated the whole fan-out on every visit
     * to the tab. Null when no repository was wired, matching [recentQuotes].
     */
    val sharedRecentQuotes: StateFlow<List<RecentQuote>> =
        (recentQuotes ?: flowOf(emptyList()))
            .stateIn(sharedScope, SharingStarted.Eagerly, emptyList())

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
            val highlights = highlightRepository

            // --- Saved quotes ---
            for (q in quotes) {
                val book = bookMap[q.bookId] ?: continue
                // The shadow-quote leak again: a highlight writes a `quote-<id>` row
                // that outlives it, and `quotes` has no is_deleted column to
                // tombstone it with — so resolve and skip.
                var quoteSpine: Int? = null
                if (highlights != null && q.highlightId.isNotBlank()) {
                    val source = highlights.getHighlight(q.highlightId)
                    if (source == null || source.isDeleted) continue
                    quoteSpine = source.spineIndex
                }
                entries.add(
                    RecentQuote(
                        id = q.id,
                        text = q.text,
                        bookTitle = book.displayTitle,
                        bookId = q.bookId,
                        sortKey = q.createdAt.toEpochMilliseconds(),
                        spineIndex = quoteSpine,
                        highlightId = q.highlightId.takeIf { it.isNotBlank() },
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
                                sortKey = h.createdAt.toEpochMilliseconds(),
                                spineIndex = h.spineIndex,
                                highlightId = h.id,
                            )
                        )
                        highlightBudget--
                    }
                }
            }

            // Sort newest-first, drop duplicates, then cap. A highlight and the shadow
            // quote it auto-creates ("quote-<id>") describe the SAME passage, and a
            // user-saved quote on that highlight is a third copy — so without this the
            // same passage showed up two or three times. Dedup by the underlying
            // highlight when known, otherwise by book + text.
            entries.sortByDescending { it.sortKey }
            val seen = HashSet<String>()
            entries.asSequence()
                .filter { rq ->
                    val key = rq.highlightId?.let { "h:$it" }
                        ?: "t:${rq.bookId}:${rq.text.trim().lowercase()}"
                    seen.add(key)
                }
                .take(FLOATING_FEED_CAP)
                .map { it.copy(text = capText(it.text)) }
                .toList()
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

        // Split sessions into book vs manga by the contentLocator discriminator
        // ([isMangaSession]): the manga reader writes `manga-page…` locators.
        val (mangaSessions, bookSessions) = sessions.partition { it.isMangaSession() }
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
        // Minutes per start-hour (minutes, not ms — the band and the chronotype
        // comparisons both want human-scale buckets).
        val hourTotals = LongArray(24)
        sessions.forEach {
            hourTotals[it.startedAt.toLocalDateTime(timeZone).hour] += it.durationMs / 60_000
        }

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
            // Two batched scans (tag names + book→tag links) instead of getTagsForBook per book.
            val tagNameById = tagRepository.getAllTags().first().associate { it.id to it.name }
            val tagIdsByBook = HashMap<String, MutableSet<String>>()
            tagRepository.getBookTagLinks().forEach { (tagId, bookIds) ->
                bookIds.forEach { b -> tagIdsByBook.getOrPut(b) { HashSet() }.add(tagId) }
            }
            val minutesByTag = LinkedHashMap<String, Long>()
            for ((bookId, minutes) in minutesByBook) {
                if (minutes <= 0L) continue
                val names = tagIdsByBook[bookId]?.mapNotNull { tagNameById[it] }.orEmpty()
                for (name in names) {
                    minutesByTag[name] = (minutesByTag[name] ?: 0L) + minutes
                }
            }
            minutesByTag.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(GENRE_CAP)
                .map { (label, minutes) -> TagSlice(label, minutes) }
        }

        // The drill core reuses the leaderboard's own per-book window minutes —
        // one aggregation, two instruments. `books` arrives exclusion-filtered
        // in both combine branches, so the core obeys §11.2 for free.

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
            longestSessionMinutes = timed.maxOfOrNull { it.durationMs / 60_000 } ?: 0L,
            daysActiveThisYear = readDays.size,
            booksOpened = minutesByBook.count { it.value > 0L },
            librarySize = books.size,
            hourTotals = hourTotals.toList(),
            todayMinutes = minutesByDay[today] ?: 0L,
            // Computed from the same exclusion-filtered session list as every other
            // figure here, so the baseline can never include a title the reader has
            // excluded from their statistics (Rule 18).
            trendSentence = com.folio.reader.ui.components.readingTrendSentence(
                com.folio.reader.ui.components.readingTrend(sessions)
            )
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
