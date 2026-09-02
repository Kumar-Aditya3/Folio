package com.folio.reader.ui.manga

import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowseItem
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.manga.mangaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock

// ---------- Browse / extensions ----------

class BrowseViewModel(
    val backend: MangaBackend,
    private val mangaRepo: MangaRepository,
) {
    val scope = mangaVmScope()

    val supportsExtensions = backend.supportsExtensions

    val sources: StateFlow<List<MangaSourceInfo>> = backend.observeSources()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val extensions: StateFlow<List<ExtensionEntry>> = backend.observeExtensions()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val repos = MutableStateFlow<List<MangaRepoInfo>>(emptyList())
    val refreshingIndex = MutableStateFlow(false)
    val installStates = MutableStateFlow<Map<String, ExtensionInstallStep>>(emptyMap())
    val nsfw = MutableStateFlow(false)

    // ---------- Global search across all sources ----------

    data class GlobalSourceResult(
        val source: MangaSourceInfo,
        val items: List<MangaBrowseItem> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        /** Relevance of the section's strongest match; sections order by this, not arrival. */
        val bestScore: Int = 0,
    )

    val globalQuery = MutableStateFlow("")
    val globalResults = MutableStateFlow<List<GlobalSourceResult>>(emptyList())
    val searchActive = MutableStateFlow(false)
    /** True while the installed-source list is still resolving before a search can fan out. */
    val preparingSources = MutableStateFlow(false)
    private var globalJob: kotlinx.coroutines.Job? = null
    private val searchGate = kotlinx.coroutines.sync.Semaphore(5)
    private val searchArrival = MutableStateFlow<List<Long>>(emptyList())
    /** Query whose results are currently held — streaming or complete. Set the moment
     *  the fan-out starts, so re-entering composition with the same query never restarts
     *  the network round-trip even while slow sources are still answering. Cleared only
     *  when the search is explicitly closed or blanked. */
    private var heldGlobalQuery: String? = null

    var globalListScrollIndex: Int = 0
    var globalListScrollOffset: Int = 0

    /**
     * Search sections in display order: sources that already found something come first,
     * ranked by how well their best result matches the query (an exact title match beats
     * a fuzzy one no matter which source answered first); ties keep arrival order so the
     * streaming feel survives. Still-searching sources follow, then errors.
     */
    val globalResultsOrdered: StateFlow<List<GlobalSourceResult>> =
        combine(globalResults, searchArrival) { list, arrival ->
            list.filter { it.items.isNotEmpty() || it.loading || it.error != null }
                .sortedWith(
                    compareByDescending<GlobalSourceResult> { it.items.isNotEmpty() }
                        .thenByDescending { it.bestScore }
                        .thenBy {
                            val idx = arrival.indexOf(it.source.id)
                            if (idx < 0) Int.MAX_VALUE else idx
                        },
                )
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    fun toggleSearch() {
        val next = !searchActive.value
        searchActive.value = next
        if (!next) {
            // Closing the search bar stops the fan-out but keeps the query text, so
            // reopening resumes where the user left off.
            globalJob?.cancel()
            globalResults.value = emptyList()
            searchArrival.value = emptyList()
            preparingSources.value = false
            heldGlobalQuery = null
        }
    }

    fun exitSearch() {
        searchActive.value = false
        globalJob?.cancel()
        globalResults.value = emptyList()
        searchArrival.value = emptyList()
        preparingSources.value = false
        heldGlobalQuery = null
    }

    fun globalSearch(query: String) {
        globalQuery.value = query
        // Skip re-search when results for this exact query are already held — or
        // still streaming in. This is what prevents LaunchedEffect(searchingSources, query)
        // from re-firing the network fan-out every time the screen re-enters composition;
        // gating on completion alone re-searched whenever a slow source was still answering.
        if (query == heldGlobalQuery && (globalResults.value.isNotEmpty() || globalJob?.isActive == true)) return
        globalJob?.cancel()
        if (query.isBlank()) {
            globalResults.value = emptyList()
            searchArrival.value = emptyList()
            preparingSources.value = false
            heldGlobalQuery = null
            return
        }
        heldGlobalQuery = query
        globalJob = scope.launch {
            preparingSources.value = true
            // Sources load asynchronously as extensions unpack; an empty snapshot would
            // silently search nothing. Await the first non-empty list instead.
            val targets = if (sources.value.isNotEmpty()) sources.value else sources.first { it.isNotEmpty() }
            globalResults.value = targets.map { GlobalSourceResult(it) }
            searchArrival.value = emptyList()
            preparingSources.value = false
            targets.map { source ->
                launch {
                    searchGate.withPermit {
                        try {
                            val page = backend.fetchBrowse(source.id, 1, BrowseMode.POPULAR, query, null)
                            // Sources answer in their own order; rank what they return so a
                            // stronger title match is never buried below a weak one. The sort
                            // is stable, so equal scores keep the source's own ordering.
                            val ranked = MangaSearchRanker.rank(page.items, query)
                            if (ranked.isNotEmpty()) {
                                searchArrival.value = searchArrival.value + source.id
                            }
                            setGlobalResult(source.id) {
                                it.copy(
                                    items = ranked,
                                    loading = false,
                                    error = if (ranked.isEmpty()) "No results" else null,
                                    bestScore = ranked.firstOrNull()
                                        ?.let { item -> MangaSearchRanker.score(query, item.title) }
                                        ?: 0,
                                )
                            }
                        } catch (e: Throwable) {
                            setGlobalResult(source.id) {
                                it.copy(loading = false, error = e.message ?: "Search failed")
                            }
                        }
                    }
                }
            }.forEach { it.join() }
        }
    }

    private fun setGlobalResult(sourceId: Long, transform: (GlobalSourceResult) -> GlobalSourceResult) {
        globalResults.value = globalResults.value.map { if (it.source.id == sourceId) transform(it) else it }
    }

    /** Opens a search result: persists it for the detail screen without adding to library. */
    suspend fun ensureEntry(source: MangaSourceInfo, item: MangaBrowseItem): MangaEntry {
        mangaRepo.findBySourceUrl(source.id, item.url)?.let { return it }
        val entry = MangaEntry(
            id = mangaId(source.id, item.url),
            sourceId = source.id,
            sourceName = source.name,
            url = item.url,
            title = item.title,
            thumbnailUrl = item.thumbnailUrl,
            inLibrary = false,
            addedAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        mangaRepo.upsert(entry)
        return entry
    }

    init {
        scope.launch { repos.value = backend.getRepos() }
        scope.launch { nsfw.value = backend.getShowNsfwSources() }
    }

    fun setNsfw(enabled: Boolean) {
        nsfw.value = enabled
        scope.launch { backend.setShowNsfwSources(enabled) }
    }

    fun refreshIndex() {
        scope.launch {
            refreshingIndex.value = true
            try {
                backend.refreshExtensionIndex()
            } finally {
                refreshingIndex.value = false
            }
        }
    }

    fun saveRepos(newRepos: List<MangaRepoInfo>) {
        scope.launch {
            backend.setRepos(newRepos)
            repos.value = backend.getRepos()
        }
    }

    fun install(pkgName: String) {
        val entry = extensions.value.firstOrNull { it.pkgName == pkgName }
        if (entry != null && entry.isNsfw && !nsfw.value) {
            setNsfw(true)
        }
        scope.launch {
            backend.installExtension(pkgName).collect { step ->
                installStates.value = installStates.value + (pkgName to step)
            }
        }
    }

    fun update(pkgName: String) {
        scope.launch {
            backend.updateExtension(pkgName).collect { step ->
                installStates.value = installStates.value + (pkgName to step)
            }
        }
    }

    fun uninstall(pkgName: String) {
        backend.uninstallExtension(pkgName)
    }

    fun trust(entry: ExtensionEntry) {
        scope.launch { backend.trustExtension(entry.pkgName, entry.versionCode, entry.signatureHash) }
    }
}

// ---------- Source browse / search ----------

class SourceBrowseViewModel(
    val backend: MangaBackend,
    val source: MangaSourceInfo,
    private val mangaRepo: MangaRepository,
    private val categoryRepo: com.folio.reader.manga.MangaCategoryRepository,
    initialQuery: String = "",
    private val onAddedToLibrary: (suspend (mangaId: String) -> Unit)? = null,
) {
    val scope = mangaVmScope()

    data class BrowseState(
        val items: List<MangaBrowseItem> = emptyList(),
        val page: Int = 1,
        val hasNextPage: Boolean = false,
        val loading: Boolean = false,
        val loadingNext: Boolean = false,
        val error: String? = null,
        val mode: BrowseMode = BrowseMode.POPULAR,
        val query: String = "",
        val filters: List<MangaFilter>? = null,
    )

    val state = MutableStateFlow(BrowseState(query = initialQuery))
    val filterTemplate = MutableStateFlow<List<MangaFilter>>(emptyList())

    var gridScrollIndex: Int = 0
    var gridScrollOffset: Int = 0

    private data class CacheKey(val sourceId: Long, val query: String, val mode: BrowseMode)
    companion object {
        private const val CACHE_MAX = 8
        private val resultCache = mutableListOf<Pair<CacheKey, BrowseState>>()
        private fun getCached(key: CacheKey): BrowseState? {
            val idx = resultCache.indexOfFirst { it.first == key }
            if (idx < 0) return null
            val entry = resultCache.removeAt(idx)
            resultCache.add(entry)
            return entry.second
        }
        private fun putCached(key: CacheKey, value: BrowseState) {
            val idx = resultCache.indexOfFirst { it.first == key }
            if (idx >= 0) resultCache.removeAt(idx)
            resultCache.add(key to value)
            while (resultCache.size > CACHE_MAX) resultCache.removeAt(0)
        }
    }

    init {
        scope.launch { filterTemplate.value = backend.getFilterTemplate(source.id) }
        val key = CacheKey(source.id, initialQuery, BrowseMode.POPULAR)
        val cached = getCached(key)
        if (cached != null && cached.items.isNotEmpty()) {
            state.value = cached.copy(loading = false, loadingNext = false)
        } else {
            reload(BrowseMode.POPULAR, initialQuery, null)
        }
    }

    fun reload(mode: BrowseMode, query: String, filters: List<MangaFilter>?) {
        scope.launch {
            val cacheKey = CacheKey(source.id, query, mode)
            val cached = getCached(cacheKey)
            if (cached != null && cached.items.isNotEmpty() && filters == null) {
                state.value = cached.copy(loading = false, loadingNext = false)
                return@launch
            }
            state.value = BrowseState(mode = mode, query = query, filters = filters, loading = true)
            try {
                val result = backend.fetchBrowse(source.id, 1, mode, query, filters)
                val newState = state.value.copy(
                    items = MangaSearchRanker.rank(result.items, query),
                    page = 1,
                    hasNextPage = result.hasNextPage,
                    loading = false,
                    error = null,
                )
                state.value = newState
                if (filters == null) {
                    putCached(cacheKey, newState)
                }
            } catch (e: Throwable) {
                state.value = state.value.copy(loading = false, error = e.message ?: "Failed to load")
            }
        }
    }

    fun loadNextPage() {
        val current = state.value
        if (current.loading || current.loadingNext || !current.hasNextPage) return
        scope.launch {
            state.value = current.copy(loadingNext = true)
            try {
                val result = backend.fetchBrowse(
                    source.id,
                    current.page + 1,
                    current.mode,
                    current.query,
                    current.filters,
                )
                state.value = state.value.copy(
                    items = state.value.items + MangaSearchRanker.rank(result.items, current.query),
                    page = current.page + 1,
                    hasNextPage = result.hasNextPage,
                    loadingNext = false,
                    error = null,
                )
            } catch (e: Throwable) {
                state.value = state.value.copy(loadingNext = false, error = e.message ?: "Failed to load")
            }
        }
    }

    suspend fun isInLibrary(item: MangaBrowseItem): Boolean =
        mangaRepo.findBySourceUrl(source.id, item.url)?.inLibrary == true

    /** Persists a tapped result so the detail screen can open it, without adding to library. */
    suspend fun ensureEntry(item: MangaBrowseItem): MangaEntry {
        mangaRepo.findBySourceUrl(source.id, item.url)?.let { return it }
        val entry = MangaEntry(
            id = mangaId(source.id, item.url),
            sourceId = source.id,
            sourceName = source.name,
            url = item.url,
            title = item.title,
            thumbnailUrl = item.thumbnailUrl,
            inLibrary = false,
            addedAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        mangaRepo.upsert(entry)
        return entry
    }

    /** Adds a browse result to the library (long-press / explicit add). */
    suspend fun addToLibrary(item: MangaBrowseItem): MangaEntry {
        val existing = mangaRepo.findBySourceUrl(source.id, item.url)
        if (existing != null) {
            mangaRepo.setInLibrary(existing.id, true)
            categoryRepo.ensureMembership(existing.id)
            notifyAddedToLibrary(existing.id)
            return existing
        }
        val entry = MangaEntry(
            id = mangaId(source.id, item.url),
            sourceId = source.id,
            sourceName = source.name,
            url = item.url,
            title = item.title,
            thumbnailUrl = item.thumbnailUrl,
            inLibrary = true,
            addedAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        mangaRepo.upsert(entry)
        categoryRepo.ensureMembership(entry.id)
        notifyAddedToLibrary(entry.id)
        return entry
    }

    private fun notifyAddedToLibrary(mangaId: String) {
        val hook = onAddedToLibrary ?: return
        scope.launch { runCatching { hook(mangaId) } }
    }

    suspend fun removeFromLibrary(item: MangaBrowseItem) {
        mangaRepo.findBySourceUrl(source.id, item.url)?.let { mangaRepo.setInLibrary(it.id, false) }
    }

    // ---------- Category prompt on add-to-library ----------

    val allCategories = categoryRepo.observeCategories()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    suspend fun categoriesFor(mangaId: String): Set<String> = categoryRepo.categoriesFor(mangaId)

    suspend fun createCategoryNamed(name: String): String? =
        runCatching { categoryRepo.create(name).id }.getOrNull()

    /** Replaces the shelf set for a manga; an empty set lands on the default shelf. */
    fun setCategoriesFor(mangaId: String, categoryIds: Set<String>) {
        scope.launch {
            val target = categoryIds.ifEmpty {
                categoryRepo.defaultCategory()?.let { setOf(it.id) } ?: return@launch
            }
            categoryRepo.assign(mangaId, target)
        }
    }
}
