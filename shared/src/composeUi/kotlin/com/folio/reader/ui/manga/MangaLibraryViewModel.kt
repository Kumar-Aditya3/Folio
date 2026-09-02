package com.folio.reader.ui.manga

import com.folio.reader.manga.ChapterNumberParser
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaCategoryRepository
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaDownloadStatus
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------- Manga library ----------

enum class MangaViewMode { GRID, LIST, COMPACT }
enum class MangaSearchScope { LIBRARY, SOURCES }
enum class MangaSortBy(val label: String) {
    RECENT("Recently opened"),
    TITLE("Title"),
    UNREAD("Unread count"),
}
enum class MangaLibFilter(val label: String) {
    UNREAD("Unread"),
    READING("Reading"),
    COMPLETED("Completed"),
    DOWNLOADED("Downloaded"),
}

private const val KEY_LIBRARY_CATEGORY = "manga.library.category"

class MangaLibraryViewModel(
    private val backend: MangaBackend,
    private val mangaRepo: MangaRepository,
    private val categoryRepo: MangaCategoryRepository,
    private val chapterRepo: MangaChapterRepository,
    private val settingsRepo: com.folio.reader.database.SettingsRepository,
    downloadRepo: com.folio.reader.manga.MangaDownloadRepository? = null,
) {
    val scope = mangaVmScope()

    val library: StateFlow<List<MangaEntry>> = mangaRepo.observeLibrary()
        .stateIn(scope, SharingStarted.Lazily, emptyList())
    val unreadCounts: StateFlow<Map<String, Int>> = chapterRepo.observeUnreadCounts()
        .stateIn(scope, SharingStarted.Lazily, emptyMap())
    val progress: StateFlow<Map<String, Float>> = chapterRepo.observeProgress()
        .stateIn(scope, SharingStarted.Lazily, emptyMap())
    val lastRead: StateFlow<Map<String, com.folio.reader.manga.MangaLastRead>> = chapterRepo.observeLastRead()
        .stateIn(scope, SharingStarted.Lazily, emptyMap())
    val downloadedCounts: StateFlow<Map<String, Int>> = chapterRepo.observeDownloadedCounts()
        .stateIn(scope, SharingStarted.Lazily, emptyMap())
    val categories = categoryRepo.observeCategories()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    /** Chapters queued or actively downloading; drives the library Downloads chip count. */
    val activeDownloadCount: StateFlow<Int> = downloadRepo?.observeQueue()
        ?.map { list -> list.count { it.status == MangaDownloadStatus.QUEUED || it.status == MangaDownloadStatus.DOWNLOADING } }
        ?.stateIn(scope, SharingStarted.Lazily, 0)
        ?: kotlinx.coroutines.flow.MutableStateFlow(0)

    val query = MutableStateFlow("")
    val searchActive = MutableStateFlow(false)
    /** One query shared by both scopes so the text survives library/source switches. */
    val searchScope = MutableStateFlow(MangaSearchScope.LIBRARY)

    // Scroll position holders: survive composition loss when navigating to detail and back.
    // Index + offset for LIST/COMPACT (LazyColumn); index + offset for GRID (LazyVerticalGrid).
    var listScrollIndex: Int = 0
    var listScrollOffset: Int = 0
    var gridScrollIndex: Int = 0
    var gridScrollOffset: Int = 0

    val sortBy = MutableStateFlow(MangaSortBy.RECENT)
    val activeFilters = MutableStateFlow<Set<MangaLibFilter>>(emptySet())
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val isSelectionMode = MutableStateFlow(false)
    val selectedCategoryId = MutableStateFlow<String?>(null)
    /** Live membership of the selected category; a stale snapshot here is what made
     *  freshly added manga invisible until the library was re-entered. */
    private val categoryMembership: StateFlow<Set<String>?> = selectedCategoryId
        .flatMapLatest { id ->
            if (id == null) flowOf<Set<String>?>(null)
            else categoryRepo.observeMangaIdsInCategory(id).map { ids -> ids as Set<String>? }
        }
        .stateIn(scope, SharingStarted.Lazily, null)

    private val baseList: StateFlow<List<MangaEntry>> =
        combine(library, query, searchActive, categoryMembership) { list, q, searching, membership ->
            list.filter { manga ->
                ((!searching || q.isBlank()) || manga.title.contains(q, ignoreCase = true)) &&
                    (membership == null || manga.id in membership)
            }
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    private val counts = combine(unreadCounts, progress, downloadedCounts) { u, p, d ->
        Triple(u, p, d)
    }

    val visible: StateFlow<List<MangaEntry>> =
        combine(baseList, counts, activeFilters, sortBy) { list, c, filters, sort ->
            val (unread, prog, _) = c
            val filtered = list.filter { manga ->
                filters.all { f ->
                    val p = prog[manga.id] ?: 0f
                    when (f) {
                        MangaLibFilter.UNREAD -> p == 0f && (unread[manga.id] ?: 0) > 0
                        MangaLibFilter.READING -> p > 0f && p < 1f
                        MangaLibFilter.COMPLETED -> p >= 1f
                        // Downloaded is a mode, not a shelf filter: the library stays
                        // whole and detail screens narrow to downloaded chapters.
                        MangaLibFilter.DOWNLOADED -> true
                    }
                }
            }
            when (sort) {
                MangaSortBy.TITLE -> filtered.sortedBy { it.title.lowercase() }
                MangaSortBy.UNREAD -> filtered.sortedByDescending { unread[it.id] ?: 0 }
                MangaSortBy.RECENT -> filtered.sortedByDescending { it.lastReadAt?.toEpochMilliseconds() ?: 0L }
            }
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    /** No virtual All bucket: the library always shows one real category. */
    fun selectCategory(categoryId: String) {
        selectedCategoryId.value = categoryId
        scope.launch { settingsRepo.setRaw(KEY_LIBRARY_CATEGORY, categoryId) }
    }

    /** Selects Main when present, otherwise the first category; used at startup and after deletes. */
    fun selectDefaultCategory() {
        scope.launch {
            val target = categoryRepo.defaultCategory() ?: return@launch
            if (selectedCategoryId.value != target.id) selectCategory(target.id)
        }
    }

    init {
        // Follow the category list so a fresh default selection lands as soon as Main
        // exists, and a deleted selection falls back to the default instead of nothing.
        scope.launch {
            categories.collect { list ->
                val current = selectedCategoryId.value
                if (list.none { it.id == current }) {
                    // First selection of the session: reopen the shelf the reader left,
                    // but only while it still exists; a gone/dirty remembered id degrades
                    // to the default rather than resurrecting a deleted category.
                    val remembered = if (current == null) settingsRepo.getRaw(KEY_LIBRARY_CATEGORY) else null
                    if (remembered != null && list.any { it.id == remembered }) selectCategory(remembered)
                    else selectDefaultCategory()
                }
            }
        }
        scope.launch {
            if (settingsRepo.getRaw(KEY_LIBRARY_DOWNLOADED_FILTER) == "true") {
                activeFilters.value = activeFilters.value + MangaLibFilter.DOWNLOADED
            }
        }
    }

    fun toggleFilter(filter: MangaLibFilter) {
        activeFilters.value = if (filter in activeFilters.value) activeFilters.value - filter else activeFilters.value + filter
        persistDownloadedMode()
    }

    fun setQuickFilter(filter: MangaLibFilter?) {
        activeFilters.value = if (filter == null) emptySet() else setOf(filter)
        persistDownloadedMode()
    }

    /** The Downloaded toggle is a library-wide mode; detail screens read it back. */
    private fun persistDownloadedMode() {
        val on = MangaLibFilter.DOWNLOADED in activeFilters.value
        scope.launch { settingsRepo.setRaw(KEY_LIBRARY_DOWNLOADED_FILTER, if (on) "true" else "false") }
    }

    suspend fun createCategory(name: String): String? =
        runCatching { categoryRepo.create(name).id }.getOrNull()

    fun renameCategory(id: String, name: String) {
        scope.launch { categoryRepo.rename(id, name) }
    }

    fun deleteCategory(id: String) {
        scope.launch {
            if (categoryRepo.delete(id) && selectedCategoryId.value == id) selectDefaultCategory()
        }
    }

    suspend fun categoriesFor(mangaId: String): Set<String> = categoryRepo.categoriesFor(mangaId)

    /** Picker apply for one manga (library item overflow menu); empty keeps the default shelf. */
    fun setCategoriesFor(mangaId: String, categoryIds: Set<String>) {
        scope.launch {
            val target = categoryIds.ifEmpty {
                categoryRepo.defaultCategory()?.let { setOf(it.id) } ?: return@launch
            }
            categoryRepo.assign(mangaId, target)
        }
    }

    /**
     * Bulk category picker, opened from the selection top bar. The initial selection is
     * the categories shared by every selected manga; saving replaces each one's set.
     */
    val bulkPickerInitial = MutableStateFlow<Set<String>?>(null)

    fun requestBulkCategories() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            val lists = ids.map { categoryRepo.categoriesFor(it) }
            bulkPickerInitial.value = lists.reduceOrNull { a, b -> a.intersect(b) } ?: emptySet()
        }
    }

    fun closeBulkPicker() {
        bulkPickerInitial.value = null
    }

    /** Live-applies the picker's current set to every selected manga; selection stays. */
    fun applyBulkCategories(categoryIds: Set<String>) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            val target = categoryIds.ifEmpty {
                categoryRepo.defaultCategory()?.let { setOf(it.id) } ?: return@launch
            }
            ids.forEach { categoryRepo.assign(it, target) }
        }
    }

    fun toggleSelection(mangaId: String) {
        val next = if (mangaId in selectedIds.value) selectedIds.value - mangaId else selectedIds.value + mangaId
        selectedIds.value = next
        isSelectionMode.value = next.isNotEmpty()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        isSelectionMode.value = false
    }

    fun removeFromLibrary(mangaId: String) {
        scope.launch { mangaRepo.setInLibrary(mangaId, false) }
    }

    fun removeSelected() {
        val ids = selectedIds.value
        scope.launch { ids.forEach { mangaRepo.setInLibrary(it, false) }; clearSelection() }
    }

    fun markSelectedRead(read: Boolean) {
        val ids = selectedIds.value
        scope.launch { ids.forEach { chapterRepo.markAllReadForManga(it, read) }; clearSelection() }
    }

    /** Marks every chapter of one series read/unread (library item overflow menu). */
    fun markOneRead(mangaId: String, read: Boolean) {
        scope.launch { chapterRepo.markAllReadForManga(mangaId, read) }
    }

    fun toggleInLibrary(manga: MangaEntry) {
        scope.launch { mangaRepo.setInLibrary(manga.id, !manga.inLibrary) }
    }

    // ---------- Library chapter updates ----------

    val updating = MutableStateFlow(false)
    val lastNewChapters = MutableStateFlow(0)
    val recentlyUpdated = MutableStateFlow<Set<String>>(emptySet())

    init {
        // Periodic new-chapter checks while the manga library is on screen.
        scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) {
                updateLibrary()
                kotlinx.coroutines.delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Polls each favourited manga's source for its chapter list, merges it with the
     * local catalog (preserving read/bookmark/progress by url) and surfaces how many
     * new chapters arrived, mirroring Mihon's library update.
     */
    fun updateLibrary() {
        if (updating.value) return
        scope.launch {
            updating.value = true
            var newTotal = 0
            val updated = mutableSetOf<String>()
            for (manga in library.value.filter { it.inLibrary }) {
                try {
                    val existing = chapterRepo.getChapters(manga.id).map { it.url }.toSet()
                    val refs = backend.fetchChapterList(manga.sourceId, manga.url)
                    val newCount = refs.count { it.url !in existing }
                    if (newCount > 0) {
                        val mapped = refs.mapIndexed { index, ref ->
                            com.folio.reader.manga.MangaChapter(
                                id = com.folio.reader.manga.chapterId(manga.id, ref.url),
                                mangaId = manga.id,
                                url = ref.url,
                                name = ref.name,
                                scanlator = ref.scanlator,
                                chapterNumber =
                                    if (ref.chapterNumber >= 0f) ref.chapterNumber
                                    else ChapterNumberParser.parse(ref.name),
                                dateUpload = ref.dateUpload,
                                sortOrder = index,
                            )
                        }
                        chapterRepo.replaceChapters(manga.id, mapped)
                        newTotal += newCount
                        updated += manga.id
                    }
                } catch (_: Throwable) {
                    // A single dead source must not abort the whole library update.
                }
            }
            lastNewChapters.value = newTotal
            recentlyUpdated.value = updated
            updating.value = false
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 30 * 60 * 1000L
    }
}
