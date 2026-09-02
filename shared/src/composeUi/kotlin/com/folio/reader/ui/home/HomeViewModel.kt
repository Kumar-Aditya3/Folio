package com.folio.reader.ui.home

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.ReadingSession
import com.folio.reader.ui.components.finishEstimate
import com.folio.reader.ui.statistics.ReadingInProgress
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.statistics.StatisticsViewModel
import com.folio.reader.ui.statistics.currentStreak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
    val continueReading: List<ReadingInProgress> = emptyList(),
    val becauseFinishedTitle: String? = null,
    val candidates: List<Book> = emptyList()
)

/**
 * §5.4 Home surface state. Shares the stats window and streak rules so the
 * Home ring and the Stats tab never disagree.
 */
class HomeViewModel(
    private val bookRepository: BookRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val settingsRepository: SettingsRepository? = null
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

    val state: Flow<HomeUiState> = combine(
        bookRepository.getAllBooks(),
        sessionRepository.observeSessionsSince(
            today().minus(DatePeriod(days = StatisticsViewModel.historyDays)).atStartOfDayIn(timeZone)
        ),
        bookRepository.getCurrentlyReading(),
        bookRepository.getFinishedBooks(),
        goalFlow
    ) { books, sessions, inProgress, finished, goal ->
        buildState(books, sessions, inProgress, finished, goal)
    }

    private fun buildState(
        books: List<Book>,
        sessions: List<ReadingSession>,
        inProgress: List<Book>,
        finished: List<Book>,
        goalMinutes: Int
    ): HomeUiState {
        val today = today()
        val weekStart = today.minus(DatePeriod(days = 6))
        val weekStartInstant = weekStart.atStartOfDayIn(timeZone)

        val minutesByDay = sessions
            .groupBy { it.startedAt.toLocalDateTime(timeZone).date }
            .mapValues { (_, group) -> group.sumOf { it.durationMs } / 60_000 }

        val readDays = sessions.mapTo(mutableSetOf()) { it.startedAt.toLocalDateTime(timeZone).date }

        val continueReading = inProgress
            .sortedByDescending { it.lastOpenedAt ?: it.updatedAt }
            .take(CONTINUE_CAP)
            .map { book ->
                ReadingInProgress(
                    book.id,
                    book.displayTitle,
                    book.displayAuthor,
                    book.normalizedProgress.toFloat(),
                    finishEstimate(
                        book.totalWords,
                        book.normalizedProgress,
                        sessions.filter { it.bookId == book.id },
                        sessions
                    ),
                    book.coverPath
                )
            }

        // Suggestions come from the most recently finished book: same series first,
        // then shared authors, never the finished book itself or other finished ones.
        val anchor = finished.maxByOrNull { it.lastOpenedAt ?: it.updatedAt }
        val candidates = if (anchor == null) emptyList() else books
            .filter { it.id != anchor.id && it.status != BookStatus.FINISHED }
            .filter { candidate ->
                (anchor.seriesId != null && candidate.seriesId == anchor.seriesId) ||
                    candidate.authors.intersect(anchor.authors.toSet()).isNotEmpty()
            }
            .take(CANDIDATE_CAP)

        val startedThisWeek = sessions
            .groupBy { it.bookId }
            .count { (_, group) -> group.minOf { it.startedAt } >= weekStartInstant }
        val finishedThisWeek = finished.count { it.updatedAt >= weekStartInstant }

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
            continueReading = continueReading,
            becauseFinishedTitle = anchor?.displayTitle,
            candidates = candidates
        )
    }

    companion object {
        /** In-progress books surfaced in "Continue reading". */
        private const val CONTINUE_CAP = 3
        /** Books suggested by "Because you finished". */
        private const val CANDIDATE_CAP = 6
    }
}
