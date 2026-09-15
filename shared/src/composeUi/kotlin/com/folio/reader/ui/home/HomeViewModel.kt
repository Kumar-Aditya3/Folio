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
import com.folio.reader.manga.MangaStatisticsRepository
import com.folio.reader.manga.MangaUpdateRepository
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.ReadingSession
import com.folio.reader.statistics.Scope
import com.folio.reader.statistics.StatsScope
import com.folio.reader.ui.components.currentStreak
import com.folio.reader.ui.components.finishEstimate
import com.folio.reader.ui.components.mangaFinishEstimate
import com.folio.reader.ui.components.mangaFinishHorizon
import com.folio.reader.ui.components.mangaPaceChaptersPerDay
import com.folio.reader.ui.statistics.ReadingInProgress
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.statistics.StatisticsViewModel
import com.folio.reader.ui.statistics.localSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Books **and** manga ranked together by last activity: the anchor is
     * `readingNow.first()` and the shelf behind it is the rest.
     *
     * [hero] and [continueReading] still describe the books-only pipeline (Stats
     * shares those rules), but Home renders this list instead, because "what am I
     * reading?" has never had a format-shaped answer. Manga exclusions apply here
     * exactly as they do on the manga cards.
     */
    val readingNow: List<ReadingNowItem> = emptyList(),
    /** §11.4: LATEST browse hits from the most recently read manga's source, cap 6. */
    val discover: List<MangaDiscoverItem> = emptyList(),
    /** True when the manga library is non-empty — Home renders for manga-only users. */
    val hasManga: Boolean = false,
    /** Rule 8: some exclusions are active — Home shows the "review" line. */
    val exclusionsActive: Boolean = false,
    /** §13.3: hero gradient is tinted from the current book's cover (Themes toggle). */
    val coverTint: Boolean = true,
    /**
     * Days since the last reading sitting (0 = today), or null when there is
     * no session history at all — the reading weather's drought signal. Derived
     * from the same gated sessions as every other number on the page.
     */
    val daysSinceLastRead: Int? = null
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
    val webUrl: String?,
    /** When this manga was last read — the key Reading now ranks books and manga by. */
    val lastReadAt: Instant = Instant.DISTANT_PAST,
    /** Long-form backlog projection for the Home anchor; null when the pace is too thin. */
    val estimate: String? = null,
    /** Short-form backlog projection for the shelf: "12 unread · ~4 days". */
    val horizon: String? = null
)

/** Which library a Reading-now card came from. */
enum class HomeItemKind { BOOK, MANGA }

/**
 * One card in Home's Reading now, whichever library it came from.
 *
 * Books and manga were two separate shelves with the same heading, which meant a
 * reader halfway through a manga had to scroll past the books they had abandoned
 * to reach it. One ranked list fixes the order; [kind] is what the UI switches on
 * for the things that genuinely differ — the plate loads from the network for
 * manga, and the primary tap opens a chapter rather than a book.
 */
data class ReadingNowItem(
    val kind: HomeItemKind,
    /** Book id or manga id. */
    val id: String,
    val title: String,
    /** Author for a book, source name for a manga. */
    val subtitle: String,
    val progress: Float,
    /** Long projection, shown by the anchor: "On pace to…". */
    val estimate: String? = null,
    /** Short caption for the shelf; books use their percentage instead. */
    val caption: String? = null,
    val coverPath: String? = null,
    /** Network cover for manga; books always render from [coverPath]. */
    val thumbnailUrl: String? = null,
    val sourceId: Long = 0L,
    val sourceName: String? = null,
    /** Last-read chapter for manga — null sends the tap to the detail screen. */
    val chapterId: String? = null,
    /** Source web page for manga, when the backend can produce one. */
    val webUrl: String? = null,
    /** Last opened (books) or last read (manga) — the ranking key. */
    val lastActivity: Instant = Instant.DISTANT_PAST
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
    private val mangaBackend: MangaBackend? = null,
    /**
     * §11.4: only used for the chapter pace behind manga predictions. Null keeps
     * the manga cards exactly as they were — captions, no projection.
     */
    private val mangaStatisticsRepository: MangaStatisticsRepository? = null
) {
    private val timeZone: TimeZone get() = TimeZone.currentSystemDefault()
    private fun today(): LocalDate = Clock.System.todayIn(timeZone)

    /** Cold so a goal edited on the Stats tab is picked up on the next Home visit. */
    private data class HomeSettings(val goalMinutes: Int, val coverTint: Boolean)

    /**
     * One settings read per emission, not two: the goal and the §13.3 cover-tint
     * toggle used to be two separate flows, each fetching and deserializing the
     * whole global-settings row before Home's first emission could assemble.
     */
    private val settingsFlow: Flow<HomeSettings> = flow {
        val settings = settingsRepository
            ?.let { repo -> runCatching { repo.getGlobalSettings() }.getOrNull() }
        emit(
            HomeSettings(
                goalMinutes = settings?.dailyGoalMinutes ?: 60,
                coverTint = settings?.homeCoverTint ?: true,
            )
        )
    }

    /** One combine emission before exclusion gating, so both pipelines share it. */
    private data class Inputs(
        val books: List<Book>,
        val sessions: List<ReadingSession>,
        val inProgress: List<Book>,
        val finished: List<Book>,
        val settings: HomeSettings
    )

    private val inputs: Flow<Inputs> = combine(
        bookRepository.getAllBooks(),
        sessionRepository.observeSessionsSince(
            today().minus(DatePeriod(days = StatisticsViewModel.historyDays)).atStartOfDayIn(timeZone)
        ),
        bookRepository.getCurrentlyReading(),
        bookRepository.getFinishedBooks(),
        settingsFlow
    ) { books, sessions, inProgress, finished, settings ->
        Inputs(books, sessions, inProgress, finished, settings)
    }

    /**
     * §11.4 Discover, off Home's critical path: the first emission is whatever
     * the cache holds (stale rows beat a blank shelf) or empty, and arrives
     * instantly; the live browse fetch — bounded at
     * [DISCOVER_FETCH_TIMEOUT_MS] but still seconds on a cold cache — runs
     * afterward and updates the surface in place. `loaded=true` is never held
     * hostage by the network again. The flow never completes while collected,
     * because `combine` tears the whole state down when any source finishes.
     */
    private val discoverFlow: Flow<List<MangaDiscoverItem>> = flow {
        // Fresh cache: one emission, no network — the "one browse request per
        // cache window" contract. Stale cache: show the stale rows immediately
        // (stale beats blank), then refresh in place. No cache: empty, then
        // fetch. The fetch only ever runs when the window has actually lapsed.
        val now = Clock.System.now()
        val cached = discoverCache
        if (cached != null) {
            val ttl = if (cached.second.isEmpty()) DISCOVER_NEGATIVE_TTL else DISCOVER_TTL
            if (now - cached.first < ttl) {
                emit(cached.second)
                awaitCancellation()
            }
            emit(cached.second)
        } else {
            emit(emptyList())
        }
        val exclusions = statsExclusionRepository?.observeExclusions()?.first() ?: emptySet()
        emit(fetchDiscover(exclusions))
        awaitCancellation()
    }

    /**
     * Home's state. The base pipeline (books, sessions, settings, exclusions,
     * the manga shelves) emits as soon as the local database answers; Discover
     * rides in on top through [discoverFlow] whenever the network gets there.
     */
    val state: Flow<HomeUiState> = run {
        val exclusionRepo = statsExclusionRepository
        val base = if (exclusionRepo == null) {
            inputs.map { value -> value.build(null, discover = cachedDiscover()) }
        } else {
            combine(inputs, exclusionRepo.observeExclusions()) { value, exclusions ->
                value.build(exclusions, discover = cachedDiscover())
            }
        }
        combine(base, discoverFlow) { value, discover ->
            if (discover.isEmpty()) value else value.copy(discover = discover)
        }
    }

    /** Discover's cached rows, however stale — the instant first paint. */
    private fun cachedDiscover(): List<MangaDiscoverItem> = discoverCache?.second.orEmpty()

    private suspend fun Inputs.build(
        exclusions: Set<Pair<Scope, String>>?,
        discover: List<MangaDiscoverItem>
    ): HomeUiState {
        val coverTint = settings.coverTint
        val today = today()
        val weekStart = today.minus(DatePeriod(days = 6))
        val weekStartInstant = weekStart.atStartOfDayIn(timeZone)

        // §12.9: resolve the scope once per emission and reuse it for every
        // selection. Direct BOOK rows also cover sessions whose book row is gone.
        // Sessions are also gated on the *local* library first (localSessions), so a
        // book deleted on this device leaves Home's numbers without waiting for sync.
        val scope = exclusions?.let(::StatsScope)
        val excluded = if (scope == null) emptySet() else excludedBookIds(books, scope)
        val directBooks = exclusions
            ?.mapNotNull { (kind, id) -> if (kind == Scope.BOOK) id else null }
            ?.toSet()
            ?: emptySet()
        val gatedSessions = localSessions(sessions, books)
            .filterNot { it.bookId in excluded || it.bookId in directBooks }
        val gatedInProgress = inProgress.filterNot { it.id in excluded }
        val gatedFinished = finished.filterNot { it.id in excluded }
        fun passes(book: Book) = book.id !in excluded && book.id !in directBooks

        val minutesByDay = gatedSessions
            .groupBy { it.startedAt.toLocalDateTime(timeZone).date }
            .mapValues { (_, group) -> group.sumOf { it.durationMs } / 60_000 }

        val readDays = gatedSessions.mapTo(mutableSetOf()) { it.startedAt.toLocalDateTime(timeZone).date }
        // Reading weather: how long since the last sitting, over the same
        // gated sessions every other number here uses. Null = no history yet.
        val lastReadDate = readDays.maxOrNull()
        val daysSinceLastRead = lastReadDate?.let { today.toEpochDays() - it.toEpochDays() }

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
        val hasManga = mangaRepository?.observeLibrary()?.first().orEmpty().isNotEmpty()
        // One ranked list for the anchor and the shelf behind it.
        val readingNow = buildReadingNow(heroBook, gatedInProgress, gatedSessions, mangaContinue)

        return HomeUiState(
            loaded = true,
            hasBooks = books.isNotEmpty(),
            hasManga = hasManga,
            goalMinutes = settings.goalMinutes,
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
            readingNow = readingNow,
            discover = discover,
            exclusionsActive = exclusions?.isNotEmpty() == true,
            coverTint = coverTint,
            daysSinceLastRead = daysSinceLastRead
        )
    }

    /**
     * §11.4/§12.4: books and manga in one list, newest activity first.
     *
     * The hero book is folded in explicitly because it can be a book that is *not*
     * in progress (the fresh-library fallback), and the merge must not silently
     * drop the one title the page is built around.
     */
    private fun buildReadingNow(
        heroBook: Book?,
        inProgress: List<Book>,
        sessions: List<ReadingSession>,
        manga: List<MangaContinueItem>
    ): List<ReadingNowItem> {
        val books = (listOfNotNull(heroBook) + inProgress)
            .distinctBy { it.id }
            .map { book ->
                ReadingNowItem(
                    kind = HomeItemKind.BOOK,
                    id = book.id,
                    title = book.displayTitle,
                    subtitle = book.displayAuthor,
                    progress = book.normalizedProgress.toFloat(),
                    estimate = finishEstimate(
                        book.totalWords,
                        book.normalizedProgress,
                        sessions.filter { it.bookId == book.id },
                        sessions
                    ),
                    coverPath = book.coverPath,
                    lastActivity = book.lastOpenedAt ?: book.updatedAt
                )
            }
        val mangaRows = manga.map { item ->
            ReadingNowItem(
                kind = HomeItemKind.MANGA,
                id = item.mangaId,
                title = item.title,
                subtitle = item.sourceName,
                progress = item.progress,
                estimate = item.estimate,
                // The projection is the more useful caption; the chapter name is the
                // fallback for a manga with nothing unread left to project.
                caption = item.horizon ?: item.caption,
                coverPath = item.coverPath,
                thumbnailUrl = item.thumbnailUrl,
                sourceId = item.sourceId,
                sourceName = item.sourceName,
                chapterId = item.chapterId,
                webUrl = item.webUrl,
                lastActivity = item.lastReadAt
            )
        }
        return (books + mangaRows)
            .sortedByDescending { it.lastActivity }
            .take(READING_NOW_CAP)
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
        // §11.4 predictions: chapters/day over the same seven-day window the book
        // pace uses, resolved against the same exclusions so a hidden manga cannot
        // inflate the projection for a visible one. Absent repository or a failed
        // query means no projection — never a guessed one.
        val chaptersPerDay = mangaStatisticsRepository?.let { repo ->
            runCatching {
                mangaPaceChaptersPerDay(repo.getStatistics(exclusions ?: emptySet()).weekReadChapters)
            }.getOrNull()
        }
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
                },
                lastReadAt = item.readAt,
                estimate = mangaFinishEstimate(unread[entry.id] ?: 0, chaptersPerDay),
                horizon = mangaFinishHorizon(unread[entry.id] ?: 0, chaptersPerDay)
            )
        }
        return rows
    }

    /**
     * §11.4 Discover fetch: LATEST from the source of the most recently read
     * library manga, minus titles already in the library, cap 6. One browse
     * request per cache window, silently absent on failure, without a backend,
     * without history, or when the source cannot serve LATEST.
     *
     * The live network half only — the cache is read by [discoverFlow] and
     * [cachedDiscover], so this runs *off* Home's first-emission path and its
     * result lands as an in-place update.
     */
    private suspend fun fetchDiscover(exclusions: Set<Pair<Scope, String>>?): List<MangaDiscoverItem> {
        val backend = mangaBackend ?: return emptyList()
        val history = mangaHistoryRepository ?: return emptyList()
        val mangaRepo = mangaRepository ?: return emptyList()
        val now = Clock.System.now()
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
            val browsePage = withTimeoutOrNull(DISCOVER_FETCH_TIMEOUT_MS) {
                backend.fetchBrowse(entry.sourceId, page = 1, mode = BrowseMode.LATEST)
            }
            // Slowness, unlike failure, is not handled by runCatching: without a
            // timeout this await can hold Home's loaded=true for OkHttp's full
            // two-minute call timeout. On timeout fall back to the last cached rows
            // (even if stale) so Discover degrades gracefully instead of stalling;
            // the labeled return still writes the fallback to the cache below.
            if (browsePage == null) return@runCatching discoverCache?.second ?: emptyList()
            browsePage.items
                .filter { it.title.trim().lowercase() !in inLibraryTitles }
                .take(DISCOVER_CAP)
                .map {
                    MangaDiscoverItem(entry.sourceId, entry.sourceName, it.url, it.title, it.thumbnailUrl)
                }
        }.getOrElse { error ->
            // runCatching absorbs CancellationException, which would mis-cache a
            // cancelled Home emission as an empty Discover result; rethrow it.
            if (error is CancellationException) throw error
            emptyList()
        }
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
     *
     * The group resolution (tags, collections) is two repository roundtrips per
     * book, which on a large library was the single slowest step of Home's first
     * emission — so it only runs when a BOOK_TAG or BOOK_COLLECTION rule exists
     * to match against. Direct, series and status rules never need it.
     */
    private suspend fun excludedBookIds(books: List<Book>, scope: StatsScope): Set<String> {
        val excluded = HashSet<String>()
        val resolveTags = scope.hasRules(Scope.BOOK_TAG)
        val resolveCollections = scope.hasRules(Scope.BOOK_COLLECTION)
        if (!resolveTags && !resolveCollections) {
            for (book in books) {
                if (!scope.includesBook(book.id, emptySet(), emptySet(), book.seriesId, book.status)) {
                    excluded.add(book.id)
                }
            }
            return excluded
        }
        val tagCache = HashMap<String, Set<String>>()
        val collectionCache = HashMap<String, Set<String>>()
        for (book in books) {
            val tagIds = if (resolveTags) {
                tagCache.getOrPut(book.id) {
                    tagRepository?.getTagsForBook(book.id)?.map { it.id }?.toSet().orEmpty()
                }
            } else {
                emptySet()
            }
            val collectionIds = if (resolveCollections) {
                collectionCache.getOrPut(book.id) {
                    collectionRepository?.getCollectionsForBook(book.id)?.map { it.id }?.toSet().orEmpty()
                }
            } else {
                emptySet()
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
        /**
         * Cards in the merged Reading now: the anchor plus the shelf behind it.
         * Six is where the shelf stops being a shelf and starts being a library.
         */
        private const val READING_NOW_CAP = 6
        /** History pool scanned before the cap, so filtered rows don't starve the card. */
        private const val RECENT_POOL = 25
        /** Browse hits surfaced in Discover (§11.4). */
        private const val DISCOVER_CAP = 6
        /**
         * Bound on the live Discover browse call: OkHttp's own call timeout is two
         * minutes, which would otherwise hold Home's loaded=true that long on a bad
         * network. Discover degrades to cache/empty instead of stalling the surface.
         */
        private const val DISCOVER_FETCH_TIMEOUT_MS = 6_000L
        /** Successful Discover results are reused for six hours (§11.4). */
        private val DISCOVER_TTL = 6.days
        /**
         * Failed/empty/timed-out Discover fetches retry after thirty minutes, not
         * five, so an offline reader does not re-pay the stall on every Home visit.
         */
        private val DISCOVER_NEGATIVE_TTL = 30.minutes

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
