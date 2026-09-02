package com.folio.reader.ui.home

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.statistics.StatsScope
import com.folio.reader.ui.components.currentStreak
import com.folio.reader.ui.components.finishEstimate
import com.folio.reader.ui.statistics.ReadingInProgress
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.statistics.StatisticsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/** Everything the Home surface renders, computed in one pass. */
data class HomeUiState(
    val loaded: Boolean = false,
    val hasBooks: Boolean = false,
    val goalMinutes: Int = 60,
    val todayMinutes: Long = 0,
    val streakDays: Int = 0,
    val week: List<StatDay> = emptyList(),
    val startedThisWeek: Int = 0,
    val finishedThisWeek: Int = 0,
    /** §12.4 hero — most recently opened in-progress book that passes StatsScope. */
    val hero: ReadingInProgress? = null,
    val continueReading: List<ReadingInProgress> = emptyList(),
    val becauseFinishedTitle: String? = null,
    val candidates: List<Book> = emptyList(),
    /** Rule 8: some exclusions are active — Home shows the "review" line. */
    val exclusionsActive: Boolean = false
)

/**
 * §5.4/§12.4 Home surface state. Shares the stats window and streak rules so the
 * Home ring and the Stats tab never disagree.
 *
 * §12.9/Rule 18: when a [StatsExclusionRepository] is wired, [StatsScope] gates
 * every content selection — hero, carousel, discovery anchor and candidates, and
 * the session-derived numbers — while `hasBooks` keeps counting the whole library
 * (the library is inventory, not a recommendation). Without the repository the
 * legacy pipeline runs untouched (desktop: numbers byte-identical).
 */
class HomeViewModel(
    private val bookRepository: BookRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val settingsRepository: SettingsRepository? = null,
    private val statsExclusionRepository: StatsExclusionRepository? = null,
    private val tagRepository: TagRepository? = null,
    private val collectionRepository: CollectionRepository? = null
) {
    private val timeZone: TimeZone get() = TimeZone.currentSystemDefault()
    private fun today(): LocalDate = Clock.System.todayIn(timeZone)

    /** Cold so a goal edited on the Stats tab is picked up on the next Home visit. */
    private val goalFlow: Flow<Int> = flow {
        val goal = settingsRepository
            ?.let { repo -> runCatching { repo.getGlobalSettings().dailyGoalMinutes }.getOrNull() }
            ?: 60
        emit(goal)
    }

    /** One combine emission before exclusion gating, so both pipelines share it. */
    private data class Inputs(
        val books: List<Book>,
        val sessions: List<ReadingSession>,
        val inProgress: List<Book>,
        val finished: List<Book>,
        val goalMinutes: Int
    )

    private val inputs: Flow<Inputs> = combine(
        bookRepository.getAllBooks(),
        sessionRepository.observeSessionsSince(
            today().minus(DatePeriod(days = StatisticsViewModel.historyDays)).atStartOfDayIn(timeZone)
        ),
        bookRepository.getCurrentlyReading(),
        bookRepository.getFinishedBooks(),
        goalFlow
    ) { books, sessions, inProgress, finished, goal ->
        Inputs(books, sessions, inProgress, finished, goal)
    }

    val state: Flow<HomeUiState> = run {
        val exclusionRepo = statsExclusionRepository
        if (exclusionRepo == null) inputs.map { it.build(null) }
        else combine(inputs, exclusionRepo.observeExclusions()) { value, exclusions ->
            value.build(exclusions)
        }
    }

    private suspend fun Inputs.build(exclusions: Set<Pair<Scope, String>>?): HomeUiState {
        val today = today()
        val weekStart = today.minus(DatePeriod(days = 6))
        val weekStartInstant = weekStart.atStartOfDayIn(timeZone)

        // §12.9: resolve the scope once per emission and reuse it for every
        // selection. Direct BOOK rows also cover sessions whose book row is gone.
        val scope = exclusions?.let(::StatsScope)
        val excluded = if (scope == null) emptySet() else excludedBookIds(books, scope)
        val directBooks = exclusions
            ?.mapNotNull { (kind, id) -> if (kind == Scope.BOOK) id else null }
            ?.toSet()
            ?: emptySet()
        val gatedSessions = sessions.filterNot { it.bookId in excluded || it.bookId in directBooks }
        val gatedInProgress = inProgress.filterNot { it.id in excluded }
        val gatedFinished = finished.filterNot { it.id in excluded }
        fun passes(book: Book) = book.id !in excluded && book.id !in directBooks

        val minutesByDay = gatedSessions
            .groupBy { it.startedAt.toLocalDateTime(timeZone).date }
            .mapValues { (_, group) -> group.sumOf { it.durationMs } / 60_000 }

        val readDays = gatedSessions.mapTo(mutableSetOf()) { it.startedAt.toLocalDateTime(timeZone).date }

        // §12.4 hero: the most recently opened in-progress book that passes. When
        // nothing is in progress (fresh library), the most recently added
        // unfinished book stands in with no progress bar. When in-progress books
        // exist but every one of them is excluded, there is deliberately NO
        // fallback to an excluded book — the designed empty shows instead.
        val heroBook = gatedInProgress.maxByOrNull { it.lastOpenedAt ?: it.updatedAt }
            ?: if (inProgress.isEmpty()) {
                books.filter { passes(it) && it.status != BookStatus.FINISHED }
                    .maxByOrNull { it.addedAt }
            } else null

        // Carousel: next in passing line, never the hero, never excluded books.
        val continueReading = gatedInProgress
            .filter { it.id != heroBook?.id }
            .sortedByDescending { it.lastOpenedAt ?: it.updatedAt }
            .take(CONTINUE_CAP)
            .map { book -> book.toReadingInProgress(gatedSessions) }

        // Suggestions come from the most recently finished passing book: same
        // series first, then shared authors — the anchor itself is never suggested.
        val anchor = gatedFinished.maxByOrNull { it.lastOpenedAt ?: it.updatedAt }
        val candidatePool = if (anchor == null) emptyList() else books
            .filter { it.id != anchor.id && it.status != BookStatus.FINISHED }
            .filter { candidate ->
                (anchor.seriesId != null && candidate.seriesId == anchor.seriesId) ||
                    candidate.authors.intersect(anchor.authors.toSet()).isNotEmpty()
            }
            .filter { passes(it) }
            .take(CANDIDATE_CAP)

        val startedThisWeek = gatedSessions
            .groupBy { it.bookId }
            .count { (_, group) -> group.minOf { it.startedAt } >= weekStartInstant }
        val finishedThisWeek = gatedFinished.count { it.updatedAt >= weekStartInstant }

        return HomeUiState(
            loaded = true,
            hasBooks = books.isNotEmpty(),
            goalMinutes = goalMinutes,
            todayMinutes = minutesByDay[today] ?: 0L,
            streakDays = currentStreak(readDays, today),
            week = (0..6).map { offset ->
                val day = weekStart.plus(DatePeriod(days = offset))
                StatDay(day, minutesByDay[day] ?: 0L)
            },
            startedThisWeek = startedThisWeek,
            finishedThisWeek = finishedThisWeek,
            hero = heroBook?.toReadingInProgress(gatedSessions),
            continueReading = continueReading,
            becauseFinishedTitle = anchor?.displayTitle,
            candidates = candidatePool,
            exclusionsActive = exclusions?.isNotEmpty() == true
        )
    }

    private fun Book.toReadingInProgress(sessions: List<ReadingSession>) = ReadingInProgress(
        id,
        displayTitle,
        displayAuthor,
        normalizedProgress.toFloat(),
        finishEstimate(totalWords, normalizedProgress, sessions.filter { it.bookId == id }, sessions),
        coverPath
    )

    /**
     * §11.2 one-way resolution — the same pass [StatisticsViewModel] runs, so
     * Home and Stats can never disagree about what counts.
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

    companion object {
        /** In-progress books surfaced in "Continue reading". */
        private const val CONTINUE_CAP = 3
        /** Books suggested by "Because you finished". */
        private const val CANDIDATE_CAP = 6
    }
}
