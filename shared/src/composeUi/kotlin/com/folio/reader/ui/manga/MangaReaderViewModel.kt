package com.folio.reader.ui.manga

import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaChapterRef
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaDownloadManager
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaHistoryRepository
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.manga.MangaRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

// ---------- Reader ----------

enum class MangaReaderMode { WEBTOON, PAGED_LTR, PAGED_RTL, PAGED_VERTICAL }

/** Global fallback for manga that has never selected its own reading mode. */
const val KEY_MANGA_READER_DEFAULT_MODE = "manga.reader.mode"

/**
 * Resolves a manga reader mode without overwriting a manga's explicit choice.
 * Existing manga settings are therefore stable; only manga without a saved mode
 * follow a subsequently changed global default.
 */
fun resolveMangaReaderMode(
    mangaModeName: String?,
    defaultModeName: String?,
): MangaReaderMode =
    mangaModeName?.let { saved -> MangaReaderMode.entries.firstOrNull { it.name == saved } }
        ?: defaultModeName?.let { saved -> MangaReaderMode.entries.firstOrNull { it.name == saved } }
        ?: MangaReaderMode.WEBTOON

data class ChapterSlot(
    val chapter: MangaChapter,
    val pages: List<MangaPageRef>,
    val startIndex: Int,
)

class MangaReaderViewModel(
    internal val backend: MangaBackend,
    internal val downloadManager: MangaDownloadManager?,
    internal val chapterRepo: MangaChapterRepository,
    internal val mangaRepo: MangaRepository,
    private val historyRepo: MangaHistoryRepository,
    internal val noteRepo: com.folio.reader.manga.MangaNoteRepository,
    private val settingsRepo: com.folio.reader.database.SettingsRepository,
    internal val fileSystem: com.folio.reader.platform.FolioFileSystem,
    internal val sessionRepo: com.folio.reader.database.ReadingSessionRepository? = null,
    internal val cycleRepo: com.folio.reader.database.ReadingCycleRepository? = null,
    private val updateRepo: com.folio.reader.manga.MangaUpdateRepository? = null,
) {
    val scope = mangaVmScope()

    /** Re-read reconcile runs at most once per chapter per reader visit (§11.5 item 5). */
    internal val reconciledFinishChapters = mutableSetOf<String>()

    val manga = MutableStateFlow<MangaEntry?>(null)
    val chapter = MutableStateFlow<MangaChapter?>(null)
    val pages = MutableStateFlow<List<MangaPageRef>>(emptyList())
    val currentIndex = MutableStateFlow(0)
    val mode = MutableStateFlow(MangaReaderMode.WEBTOON)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val showControls = MutableStateFlow(true)
    val notesRevision = MutableStateFlow(0)
    val localPage = MutableStateFlow(0)
    val localCount = MutableStateFlow(0)
    val extendingForward = MutableStateFlow(false)

    /**
     * Explicit one-shot seeks (resume on open, slider). [currentIndex] is the reading
     * position tracker only — driving scrolls from it feeds back into itself, because
     * visible-page tracking writes it during normal scrolling, and chapter prepends
     * shift it. That loop snapped the webtoon viewport mid-scroll (visible page jumps).
     */
    internal val seekChannel = Channel<Int>(Channel.CONFLATED)
    val seekRequests = seekChannel.receiveAsFlow()

    /** Reader zoom shared by the whole flow: webtoon widens every page, paged modes scale the sheet. */
    val zoom = MutableStateFlow(1f)

    fun setZoom(level: Float) {
        // Webtoon may zoom out below fit-width for a thinner, longer stream;
        // paged modes keep the fit floor so the sheet never shrinks away.
        val min = if (mode.value == MangaReaderMode.WEBTOON) 0.5f else 1f
        zoom.value = level.coerceIn(min, 3f)
    }

    fun resetZoom() {
        zoom.value = 1f
    }

    internal var mangaId: String = ""
    internal var sourceId: Long = 0L
    internal var activeSession: com.folio.reader.model.ReadingSession? = null
    private var lastProgressSaveMs: Long = 0L

    internal var navList: List<MangaChapter> = emptyList()
    internal var slots: MutableList<ChapterSlot> = mutableListOf()
    /** Slot assembly runs across background coroutines (prev/next splice in parallel);
     *  every read and write of [slots] goes through [withSlots]. */
    internal val slotsLock = Any()
    internal inline fun <T> withSlots(block: MutableList<ChapterSlot>.() -> T): T =
        synchronized(slotsLock) { slots.block() }
    private val failedChapters = mutableSetOf<String>()
    internal var isAtEndOfNav = false
    private var extendingBackward = false
    private var openJob: Job? = null

    /** Index (in [slots]) of the slot holding the current page; forward moves past a
     *  slot finalize that chapter as read. */
    private var lastSlotIdx = 0
    internal val finalizedChapters = mutableSetOf<String>()

    // ---------- Position-aware chapter loading ----------

    /** Page lists fetched ahead of use, keyed by chapter id. Bounded; eldest evicted. */
    private val pageListCache = LinkedHashMap<String, List<MangaPageRef>>()
    /** Chapter-list fetches are network round trips; keep them to a small window. */
    private val pageListGate = Semaphore(2)
    private var prefetchJob: Job? = null

    // ---------- Position-aware page image prefetch ----------

    internal val imageGate = Semaphore(IMAGE_PREFETCH_CONCURRENCY)
    internal var imagePrefetchJob: Job? = null
    internal val pageBytes = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    internal var pageBytesTotal = 0L
    internal val inflightBytes = HashMap<String, kotlinx.coroutines.Deferred<ByteArray?>>()

    internal val progressQueue = Channel<ProgressSnapshot>(Channel.UNLIMITED)
    internal val progressMutex = Mutex()
    internal var saveWorker: Job? = null

    fun setMode(newMode: MangaReaderMode) {
        mode.value = newMode
        val id = mangaId
        scope.launch { settingsRepo.setRaw(readerModeKey(id), newMode.name) }
    }

    private fun readerModeKey(mangaId: String) = "$KEY_MANGA_READER_DEFAULT_MODE.$mangaId"

    fun toggleBookmark() {
        scope.launch {
            val c = chapter.value ?: return@launch
            chapterRepo.setBookmarked(c.id, !c.bookmarked)
            chapter.value = chapterRepo.getChapter(c.id)
        }
    }

    fun open(manga: MangaEntry, chapter: MangaChapter) {
        this.mangaId = manga.id
        this.sourceId = manga.sourceId
        this.manga.value = manga
        this.chapter.value = chapter
        currentIndex.value = 0
        error.value = null
        // §11.3: the reader has now shown this manga's chapters — clear the
        // Home "new chapters" badge. Guarded so a repo failure can't break open.
        updateRepo?.let { repo ->
            scope.launch { runCatching { repo.clearNewChapters(manga.id) } }
        }
        startSession(manga, chapter)
        openJob?.cancel()
        prefetchJob?.cancel()
        imagePrefetchJob?.cancel()
        openJob = scope.launch {
            // The manga owns its reading mode; the first open snapshots the
            // current default onto it so later default changes don't reach back.
            val modeKey = readerModeKey(manga.id)
            val savedName = settingsRepo.getRaw(modeKey)
            val resolved = resolveMangaReaderMode(
                mangaModeName = savedName,
                defaultModeName = settingsRepo.getRaw(KEY_MANGA_READER_DEFAULT_MODE),
            )
            mode.value = resolved
            if (savedName == null) {
                runCatching { settingsRepo.setRaw(modeKey, resolved.name) }
            }
            loading.value = true
            try {
                val allChapters = chapterRepo.getChapters(manga.id)
                val savedFilterName = settingsRepo.getRaw("$KEY_CHAPTER_FILTER.${manga.id}")
                val filter = ChapterFilter.entries.firstOrNull { it.name == savedFilterName } ?: ChapterFilter.ALL
                // Navigation always runs in story order (chapter numbers, recovered
                // from titles when unset); the detail screen's display sort and the
                // source's own list direction (some list newest-first) never change it.
                val filtered = filterChapters(allChapters, filter).sortedWith(STORY_ORDER)
                navList = if (filtered.any { it.id == chapter.id }) filtered
                    else allChapters.sortedWith(STORY_ORDER)

                val navIdx = navList.indexOfFirst { it.id == chapter.id }
                withSlots {
                    clear()
                    failedChapters.clear()
                }
                finalizedChapters.clear()
                isAtEndOfNav = false

                // Current fetches alone first so the opened chapter can never queue behind
                // a neighbour at the 2-permit gate; prev and next then share the permits —
                // prev is awaited before the first publish, next appends asynchronously
                // (appending never shifts existing indices).
                val prevCh = navList.getOrNull(navIdx - 1)
                val nextCh = navList.getOrNull(navIdx + 1)

                val currentSlot = pageListGate.withPermit { fetchSlotCached(navList[navIdx]) }
                if (currentSlot == null) {
                    // The chapter isn't downloaded (downloaded ones never fail here), so
                    // this is a live source fetch that died — usually no connection. Say
                    // so instead of a bare failure that retrying offline just repeats.
                    error.value = "Couldn't reach the source. Downloaded chapters can be read offline."
                    loading.value = false
                    return@launch
                }
                val prevDeferred = prevCh?.let { ch -> async { pageListGate.withPermit { fetchSlotCached(ch) } } }
                val nextDeferred = nextCh?.let { ch -> async { pageListGate.withPermit { fetchSlotCached(ch) } } }
                val prevSlot = prevDeferred?.await()

                var offset = 0
                if (prevSlot != null) {
                    withSlots { add(prevSlot.copy(startIndex = 0)) }
                    offset = prevSlot.pages.size
                    lastSlotIdx = 1
                } else {
                    lastSlotIdx = 0
                }
                withSlots { add(currentSlot.copy(startIndex = offset)) }
                // Restore the saved page (offset by the prepended chapter) BEFORE publishing
                // so the first composition already points at the resume position.
                val resumeLocal =
                    if (chapter.lastPageRead in 1 until currentSlot.pages.size) chapter.lastPageRead else 0
                currentIndex.value = offset + resumeLocal
                seekChannel.trySend(offset + resumeLocal)
                publishPages()
                updateActiveChapter()
                historyRepo.record(
                    mangaId = manga.id,
                    chapterId = chapter.id,
                    title = manga.title,
                    coverUrl = manga.thumbnailUrl,
                    coverPath = manga.coverPath,
                    sourceName = manga.sourceName,
                    chapterName = chapter.name,
                )
                loading.value = false
                scheduleImagePrefetch()

                launch {
                    val slot = nextDeferred?.await()
                    if (slot != null) {
                        appendSlot(slot)
                    } else if (nextCh == null) {
                        isAtEndOfNav = true
                    }
                    scheduleChapterPrefetch()
                    scheduleImagePrefetch()
                }
            } catch (e: Throwable) {
                error.value = e.message ?: "Failed to load pages"
                loading.value = false
            }
        }
    }

    private fun prependSlot(slot: ChapterSlot) {
        withSlots {
            if (any { it.chapter.id == slot.chapter.id }) return
            val added = slot.pages.size
            for (i in indices) {
                set(i, this[i].copy(startIndex = this[i].startIndex + added))
            }
            add(0, slot.copy(startIndex = 0))
        }
        currentIndex.value = currentIndex.value + slot.pages.size
        lastSlotIdx += 1
        publishPages()
    }

    private fun appendSlot(slot: ChapterSlot) {
        withSlots {
            if (any { it.chapter.id == slot.chapter.id }) return
            val last = last()
            add(slot.copy(startIndex = last.startIndex + last.pages.size))
        }
        if (navList.lastOrNull()?.id == slot.chapter.id) isAtEndOfNav = true
        publishPages()
    }

    private suspend fun fetchSlotCached(chapter: MangaChapter): ChapterSlot? {
        if (chapter.id in failedChapters) return null
        val cached = synchronized(pageListCache) { pageListCache[chapter.id] }
        if (cached != null) return ChapterSlot(chapter, cached, 0)
        return fetchSlot(chapter)
    }

    private suspend fun fetchSlot(chapter: MangaChapter): ChapterSlot? {
        // Only a completed download is served from disk: a chapter still downloading has
        // a partial file set, and trusting the folder would present a truncated chapter
        // whose short page list then gets cached. Individual already-written pages still
        // resolve from disk below (resolvePageBytes), so a live download and reading can
        // coexist without ever shrinking the chapter.
        val downloadComplete = downloadManager?.isChapterDownloaded(chapter.id) == true
        val localPages = if (downloadComplete) {
            downloadManager?.downloadedPageCount(mangaId, chapter.id) ?: 0
        } else 0
        if (localPages > 0) {
            val pages = (0 until localPages).map { MangaPageRef(index = it) }
            cachePageList(chapter.id, pages)
            return ChapterSlot(chapter = chapter, pages = pages, startIndex = 0)
        }
        return try {
            val ref = MangaChapterRef(url = chapter.url, name = chapter.name, chapterNumber = chapter.chapterNumber)
            val pages = backend.fetchPageList(sourceId, ref)
            cachePageList(chapter.id, pages)
            ChapterSlot(chapter = chapter, pages = pages, startIndex = 0)
        } catch (_: Throwable) {
            failedChapters.add(chapter.id)
            null
        }
    }

    private fun cachePageList(chapterId: String, pages: List<MangaPageRef>) {
        synchronized(pageListCache) {
            pageListCache[chapterId] = pages
            while (pageListCache.size > PAGE_LIST_CACHE_MAX) {
                pageListCache.keys.firstOrNull()?.let(pageListCache::remove)
            }
        }
    }

    /**
     * Keeps a rolling window of chapter page lists warm around the reading position:
     * next, next+1, prev, next+2 — in that priority. Replaces the previous job so a
     * position change cancels fetches that are no longer nearby.
     */
    private fun scheduleChapterPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val current = activeSlot() ?: return@launch
            val navIdx = navList.indexOfFirst { it.id == current.chapter.id }
            if (navIdx < 0) return@launch
            val loaded = withSlots { mapTo(mutableSetOf()) { it.chapter.id } }
            listOf(navIdx + 1, navIdx + 2, navIdx - 1, navIdx + 3)
                .distinct()
                .filter { it in navList.indices }
                .map { navList[it] }
                .filter { it.id !in loaded && it.id !in failedChapters }
                .forEach { ch ->
                    launch {
                        pageListGate.withPermit { fetchSlotCached(ch) }
                    }
                }
        }
    }

    private fun publishPages() {
        val all = withSlots { flatMap { it.pages } }
        pages.value = all
        localCount.value = activeSlot()?.pages?.size ?: 0
        updateLocals()
    }

    private fun updateLocals() {
        val slot = activeSlot() ?: return
        val local = currentIndex.value - slot.startIndex
        localPage.value = local
        localCount.value = slot.pages.size
    }

    private fun updateActiveChapter() {
        val slot = activeSlot() ?: return
        if (chapter.value?.id != slot.chapter.id) {
            chapter.value = slot.chapter
        }
        updateLocals()
    }

    fun extendForward() {
        if (extendingForward.value || isAtEndOfNav) return
        scope.launch {
            extendingForward.value = true
            try {
                val lastSlot = withSlots { lastOrNull() } ?: return@launch
                val lastNavIdx = navList.indexOfFirst { it.id == lastSlot.chapter.id }
                val nextNavIdx = lastNavIdx + 1
                if (lastNavIdx < 0 || nextNavIdx >= navList.size) {
                    isAtEndOfNav = true
                    return@launch
                }
                val slot = fetchSlotCached(navList[nextNavIdx])
                if (slot != null) {
                    appendSlot(slot)
                    scheduleChapterPrefetch()
                    scheduleImagePrefetch()
                } else if (nextNavIdx + 1 >= navList.size) {
                    isAtEndOfNav = true
                }
            } finally {
                extendingForward.value = false
            }
        }
    }

    fun extendBackward() {
        if (mode.value != MangaReaderMode.WEBTOON) return
        if (extendingBackward) return
        val firstSlot = withSlots { firstOrNull() } ?: return
        val firstNavIdx = navList.indexOfFirst { it.id == firstSlot.chapter.id }
        if (firstNavIdx <= 0) return
        extendingBackward = true
        scope.launch {
            try {
                val slot = fetchSlotCached(navList[firstNavIdx - 1]) ?: return@launch
                prependSlot(slot)
            } finally {
                extendingBackward = false
            }
        }
    }

    suspend fun close() {
        // Authoritative final write: let any queued snapshot land first, then persist the
        // current position. Resume logic (nextChapterToRead) must never observe a stale
        // position because a throttled save was dropped or reordered.
        progressMutex.withLock {
            while (true) {
                val pending = progressQueue.tryReceive().getOrNull() ?: break
                writeSnapshot(pending)
            }
            currentSnapshot()?.let { writeSnapshot(it) }
        }
        val repo = sessionRepo ?: return
        val session = activeSession ?: return
        activeSession = null
        val now = kotlinx.datetime.Clock.System.now()
        val ended = session.copy(
            endedAt = now,
            durationMs = (now - session.startedAt).inWholeMilliseconds,
            isActive = false,
        )
        runCatching { repo.updateSession(ended) }
    }

    fun onPageChanged(index: Int) {
        val slot = slotForIndex(index) ?: return
        val changed = currentIndex.value != index
        currentIndex.value = index
        val slotIdx = withSlots { indexOf(slot) }
        if (slotIdx > lastSlotIdx) {
            // Forward move past one or more chapters: everything left behind is read.
            // Only the slots actually passed are finalized — never chapters that sat
            // before the session's starting point.
            finalizePassedSlots(lastSlotIdx, slotIdx)
        }
        lastSlotIdx = slotIdx
        if (chapter.value?.id != slot.chapter.id) {
            chapter.value = slot.chapter
            scope.launch {
                val m = manga.value
                historyRepo.record(
                    mangaId = mangaId,
                    chapterId = slot.chapter.id,
                    title = m?.title ?: "",
                    coverUrl = m?.thumbnailUrl,
                    coverPath = m?.coverPath,
                    sourceName = m?.sourceName,
                    chapterName = slot.chapter.name,
                )
            }
            // A chapter crossing is authoritative state; persist it outside the throttle.
            enqueueProgress()
        } else if (changed) {
            val local = index - slot.startIndex
            val total = slot.pages.size
            val atEnd = total > 0 && local >= total - 1
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            // The final page always persists: dropping it is what stranded chapters as
            // unread and broke Continue Reading.
            if (atEnd || now - lastProgressSaveMs > PROGRESS_SAVE_INTERVAL_MS) {
                lastProgressSaveMs = now
                enqueueProgress()
            }
        }
        updateLocals()
        checkExtensions(index)
        scheduleImagePrefetch()
    }

    fun toggleControls() {
        showControls.value = !showControls.value
    }

    companion object {
        private const val PAGE_LIST_CACHE_MAX = 8
        private const val IMAGE_PREFETCH_CONCURRENCY = 3
        private const val PROGRESS_SAVE_INTERVAL_MS = 2000L
    }
}
