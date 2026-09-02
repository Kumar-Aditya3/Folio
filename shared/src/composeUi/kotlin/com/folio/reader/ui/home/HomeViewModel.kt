package com.folio.reader.ui.home

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.database.StatsExclusionRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaCategoryRepository
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaHistoryRepository
import com.folio.reader.manga.MangaNewChapterBadge
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.MangaUpdateRepository
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
import kotlinx.coroutines.flow.first
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

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
    /** §11.4: library manga with new chapters, newest check first — empty hides the card. */
    val newChapters: List<MangaNewChapterBadge> = emptyList(),
    /** §11.4: manga Continue reading — recently read library manga, cap 3. */
    val mangaContinue: List<MangaContinueItem> = emptyList(),
    /** §11.4: LATEST browse hits from the most recently read manga's source, cap 6. */
    val discover: List<MangaDiscoverItem> = emptyList(),
    /** True when the manga library is non-empty — Home renders for manga-only users. */
    val hasManga: Boolean = false,
    /** Rule 8: some exclusions are active — Home shows the "review" line. */
    val exclusionsActive: Boolean = false
)

/** One row of the manga Continue-reading card (§11.4). */
data class MangaContinueItem(
    val mangaId: String,
    val title: String,
    val thumbnailUrl: String?,
    val coverPath: String?,
    val sourceId: Long,
    val sourceName: String,
    /** Last-read chapter — the primary tap opens the reader here, detail when null. */
    val chapterId: String?,
    val caption: String?,
    val progress: Float,
    /** Source web page, when the backend can produce one — drives the overflow item. */
    val webUrl: String?
)

/** One Discover row (§11.4): a LATEST browse hit not already in the library. */
data class MangaDiscoverItem(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String?
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
    private val collectionRepository: CollectionRepository? = null,
    private val mangaUpdateRepository: MangaUpdateRepository? = null,
    private val mangaHistoryRepository: MangaHistoryRepository? = null,
    private val mangaRepository: MangaRepository? = null,
    private val mangaChapterRepository: MangaChapterRepository? = null,
    private val mangaCategoryRepository: MangaCategoryRepository? = null,
    private val mangaBackend: MangaBackend? = null
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

        // §11.4: the update repository resolves the same exclusions against the
        // manga side; null on desktop keeps the legacy shape.
        val newChapters = mangaUpdateRepository
            ?.getNewChapterBadges(exclusions ?: emptySet())
            .orEmpty()
        val mangaContinue = buildMangaContinue(exclusions)
        val discover = buildDiscover(exclusions)
        val hasManga = mangaRepository?.observeLibrary()?.first().orEmpty().isNotEmpty()

        return HomeUiState(
            loaded = true,
            hasBooks = books.isNotEmpty(),
            hasManga = hasManga,
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
            newChapters = newChapters,
            mangaContinue = mangaContinue,
            discover = discover,
            exclusionsActive = exclusions?.isNotEmpty() == true
        )
    }

    /**
     * §11.4 manga Continue reading: the most recently read library manga, cap 3,
     * same gating as the badge card — history rows without a library entry and
     * out-of-library reads never surface, and §11.2 manga exclusions hide their
     * titles here too (Rule 18).
     */
    private suspend fun buildMangaContinue(
        exclusions: Set<Pair<Scope, String>>?
    ): List<MangaContinueItem> {
        val history = mangaHistoryRepository ?: return emptyList()
        val mangaRepo = mangaRepository ?: return emptyList()
        val chapterRepo = mangaChapterRepository ?: return emptyList()
        val scope = exclusions?.let(::StatsScope)
        val progress = chapterRepo.observeProgress().first()
        val unread = chapterRepo.observeUnreadCounts().first()
        val rows = mutableListOf<MangaContinueItem>()
        for (item in history.observeRecent(RECENT_POOL).first().distinctBy { it.mangaId }) {
            if (rows.size >= MANGA_CONTINUE_CAP) break
            val entry = mangaRepo.get(item.mangaId) ?: continue
            if (!entry.inLibrary) continue
            val categories = mangaCategoryRepository?.categoriesFor(entry.id).orEmpty()
            if (scope?.includesManga(entry.id, categories, entry.sourceId) == false) continue
            rows += MangaContinueItem(
                mangaId = entry.id,
                title = entry.title,
                thumbnailUrl = entry.thumbnailUrl,
                coverPath = entry.coverPath,
                sourceId = entry.sourceId,
                sourceName = entry.sourceName,
                chapterId = item.chapterId,
                caption = item.chapterName?.takeIf { it.isNotBlank() }
                    ?: unread[entry.id]?.takeIf { it > 0 }?.let { "$it unread" },
                progress = (progress[entry.id] ?: 0f).coerceIn(0f, 1f),
                webUrl = mangaBackend?.let { backend ->
                    runCatching { backend.sourceWebUrl(entry.sourceId, entry.url) }.getOrNull()
                }
            )
        }
        return rows
    }

    /**
     * §11.4 Discover: LATEST from the source of the most recently read library
     * manga, minus titles already in the library, cap 6. One browse request per
     * cache window, silently absent on failure, without a backend, without
     * history, or when the source cannot serve LATEST.
     */
    private suspend fun buildDiscover(exclusions: Set<Pair<Scope, String>>?): List<MangaDiscoverItem> {
        val backend = mangaBackend ?: return emptyList()
        val history = mangaHistoryRepository ?: return emptyList()
        val mangaRepo = mangaRepository ?: return emptyList()
        val now = Clock.System.now()
        discoverCache?.let { (cachedAt, rows) ->
            val ttl = if (rows.isEmpty()) DISCOVER_NEGATIVE_TTL else DISCOVER_TTL
            if (now - cachedAt < ttl) return rows
        }
        val fetched: List<MangaDiscoverItem> = runCatching {
            val recent = history.observeRecent(1).first().firstOrNull() ?: return emptyList()
            val entry = mangaRepo.get(recent.mangaId) ?: return emptyList()
            if (!entry.inLibrary || entry.isLocal) return emptyList()
            val categories = mangaCategoryRepository?.categoriesFor(entry.id).orEmpty()
            if (exclusions != null &&
                !StatsScope(exclusions).includesManga(entry.id, categories, entry.sourceId)
            ) return emptyList()
            val source = backend.observeSources().first().firstOrNull { it.id == entry.sourceId }
            if (source?.supportsLatest != true) return emptyList()
            val inLibraryTitles = mangaRepo.observeLibrary().first()
                .map { it.title.trim().lowercase() }
                .toSet()
            backend.fetchBrowse(entry.sourceId, page = 1, mode = BrowseMode.LATEST)
                .items
                .filter { it.title.trim().lowercase() !in inLibraryTitles }
                .take(DISCOVER_CAP)
                .map {
                    MangaDiscoverItem(entry.sourceId, entry.sourceName, it.url, it.title, it.thumbnailUrl)
                }
        }.getOrDefault(emptyList())
        discoverCache = now to fetched
        return fetched
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
        /** Manga surfaced in the manga Continue-reading card (§11.4). */
        private const val MANGA_CONTINUE_CAP = 3
        /** History pool scanned before the cap, so filtered rows don't starve the card. */
        private const val RECENT_POOL = 25
        /** Browse hits surfaced in Discover (§11.4). */
        private const val DISCOVER_CAP = 6
        /** Successful Discover results are reused for six hours (§11.4). */
        private val DISCOVER_TTL = 6.days
        /** Failed/empty Discover fetches retry after five minutes, not six hours. */
        private val DISCOVER_NEGATIVE_TTL = 5.minutes

        /**
         * The Discover cache must outlive Home's composition — HomeRoute recreates
         * the view model per visit, so an instance field would re-fetch every time.
         */
        @Volatile
        private var discoverCache: Pair<Instant, List<MangaDiscoverItem>>? = null

        internal fun resetDiscoverCache() {
            discoverCache = null
        }
    }
}
