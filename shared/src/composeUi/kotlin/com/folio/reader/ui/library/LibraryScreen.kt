package com.folio.reader.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.reader.ml.SearchMode
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentCategory
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.model.Series
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioFadeSwap
import com.folio.reader.ui.components.folioSizeTransformEligible
import com.folio.reader.ui.components.folioSwapSizeTransform
import com.folio.reader.ui.components.FolioSharedElementsSuppressed
import com.folio.reader.ui.components.rememberSwapInFlight
import com.folio.reader.ui.manga.MangaLibraryRail
import com.folio.reader.ui.search.BookHit
import com.folio.reader.ui.search.BookSearchController
import com.folio.reader.ui.search.BookSearchResultsList
import com.folio.reader.ui.search.SearchScope
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioTopInset
import com.folio.reader.ui.theme.folioBarTopInset
import com.folio.reader.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.launch

/** Top-level library category. Names are persisted, so existing values must remain stable. */
enum class LibraryMode { BOOKS, DOCUMENTS, MANGA }

internal val libraryModeDisplayOrder = listOf(
    LibraryMode.BOOKS,
    LibraryMode.MANGA,
    LibraryMode.DOCUMENTS
)

internal fun libraryModeDisplayIndex(mode: LibraryMode): Int =
    libraryModeDisplayOrder.indexOf(mode).coerceAtLeast(0)

internal fun libraryModeAtDisplayIndex(index: Int): LibraryMode =
    libraryModeDisplayOrder.getOrElse(index) { LibraryMode.BOOKS }

fun libraryModeFromPersistedName(name: String?): LibraryMode =
    LibraryMode.entries.firstOrNull { it.name == name } ?: LibraryMode.BOOKS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Book) -> Unit,
    onBookDetailClick: (Book) -> Unit,
    onImportClick: () -> Unit,
    /** Toggles the current shelf's rail search (books and documents; manga uses [onMangaSearchClick]). */
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    showSettingsAction: Boolean = true,
    onTagManagerClick: () -> Unit = {},
    onQuoteBrowserClick: () -> Unit = {},
    onRevisitClick: () -> Unit = {},
    onDeleteBooks: (Set<String>) -> Unit = {},
    onShareBooks: (Set<String>) -> Unit = {},
    onSetBookStatus: (Set<String>, BookStatus) -> Unit = { _, _ -> },
    viewModel: LibraryViewModel,
    /**
     * The unfiltered library, collected by the host for the app's whole lifetime
     * (`FolioNavModelImpl.libraryBooks`). Read for two things: the rail's books
     * search, and — more importantly — the value the shelf's own flow *seeds*
     * from, so the opening frame of a visit already shows the real shelf instead
     * of the skeleton. See [libraryShelfSeed] for why that matters and
     * `libraryBooks` for what it costs when it is missing.
     *
     * Deliberately a `StateFlow` and not a bare `Flow`: the seed has to be
     * correct on the destination's *first* composition, and `collectAsState`
     * cannot hand over a value until the frame after that — which is one frame
     * of skeleton inside the morph all over again, the exact thing this exists
     * to remove. The current value is read synchronously; the `collectAsState`
     * below only drives later updates.
     *
     * Null on hosts that do not hoist it (desktop, tests): the screen then falls
     * back to its own cold read and behaves exactly as it did before.
     */
    knownBooks: kotlinx.coroutines.flow.StateFlow<List<Book>>? = null,
    syncState: com.folio.reader.sync.SyncState? = null,
    onSyncNow: () -> Unit = {},
    libraryMode: LibraryMode = LibraryMode.BOOKS,
    onLibraryModeChange: (LibraryMode) -> Unit = {},
    booksViewMode: LibraryViewModel.ViewMode = LibraryViewModel.ViewMode.GRID,
    onBooksViewModeChange: (LibraryViewModel.ViewMode) -> Unit = {},
    preserveFeaturedBookDuringSelection: Boolean = false,
    documentLibraryViewModel: DocumentLibraryViewModel? = null,
    onDocumentImportClick: () -> Unit = {},
    onDocumentOpen: (Document) -> Unit = {},
    onDocumentDelete: (Document) -> Unit = {},
    onShareDocuments: (Set<String>) -> Unit = {},
    mangaContent: (@Composable () -> Unit)? = null,
    mangaExtensionsAvailable: Boolean = false,
    onMangaBrowseClick: () -> Unit = {},
    onMangaExtensionsClick: () -> Unit = {},
    onMangaHistoryClick: () -> Unit = {},
    onMangaImportClick: () -> Unit = {},
    onMangaSearchClick: () -> Unit = {},
    onMangaBackupImport: () -> Unit = {},
    onMangaBackupExport: () -> Unit = {},
    mangaViewMode: com.folio.reader.ui.manga.MangaViewMode = com.folio.reader.ui.manga.MangaViewMode.GRID,
    onMangaViewModeChange: (com.folio.reader.ui.manga.MangaViewMode) -> Unit = {},
    mangaLibraryViewModel: com.folio.reader.ui.manga.MangaLibraryViewModel? = null,
    onRemoveSelectedManga: ((Set<String>) -> Unit)? = null,
    onOpenStats: (() -> Unit)? = null,
    /** Books rail search: the field lives on the rail and never removes the mode switch. */
    bookSearchActive: Boolean = false,
    onBookSearchActiveChange: (Boolean) -> Unit = {},
    bookSearchController: BookSearchController? = null,
    onOpenBookHit: (BookHit) -> Unit = {},
    /** Documents rail search: same pattern as books, backed by the document query flow. */
    documentSearchActive: Boolean = false,
    onDocumentSearchActiveChange: (Boolean) -> Unit = {},
    /** Manga rail search + chrome, hosted here for the same reason as the other two. */
    mangaSearchActive: Boolean = false,
    onMangaSearchActiveChange: (Boolean) -> Unit = {},
    mangaSourcesAvailable: Boolean = false,
    onOpenMangaDownloads: () -> Unit = {},
) {
    var sortBy by remember { mutableStateOf(LibraryViewModel.SortBy.LAST_OPENED) }
    var sortAscending by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(LibraryViewModel.FilterState()) }
    // Selection lives in the view model so the host's system-back handler can
    // clear it instead of falling through and exiting the app.
    val selectedBooks by viewModel.selectedBookIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    // Collection shelves: the manga/documents category row, over collections.
    // The selection is hoisted in the view model, so it survives navigation and
    // is remembered per device across launches.
    val shelfCollections by viewModel.collections.collectAsState()
    val selectedCollectionId by viewModel.selectedCollectionId.collectAsState()
    var manageCollectionsOpen by remember { mutableStateOf(false) }
    // Bulk collections: non-null while the picker is open (the manga/document
    // picker's `bulkPickerInitial` contract — the value is the shared check set).
    val bulkCollectionPickerInitial by viewModel.bulkCollectionPickerInitial.collectAsState()
    var overflowOpen by remember { mutableStateOf(false) }
    var mangaOverflowOpen by remember { mutableStateOf(false) }
    // View mode, sort and (manga) shelf filters live in their own "Display" menu.
    // They used to share the overflow with Tags, Revisit, Browse sources, history
    // and both backup routes, which made a twenty-item menu that ran the height of
    // the screen — the manga one especially.
    var displayOpen by remember { mutableStateOf(false) }
    var mangaDisplayOpen by remember { mutableStateOf(false) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var documentToDelete by remember { mutableStateOf<Document?>(null) }
    var documentPicker by remember { mutableStateOf<Pair<Document, Set<String>>?>(null) }
    var manageDocumentCategories by remember { mutableStateOf(false) }
    val documentCategories by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.categories
            ?: kotlinx.coroutines.flow.MutableStateFlow<List<DocumentCategory>>(emptyList())
    }.collectAsState(initial = emptyList())
    val selectedDocumentCategory by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.selectedCategoryId
            ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }.collectAsState(initial = null)
    val selectedDocumentIds by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.selectedIds
            ?: kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    }.collectAsState(initial = emptySet())
    val documentSelectionActive by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.isSelectionMode
            ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }.collectAsState(initial = false)
    val documentBulkInitial by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.bulkPickerInitial
            ?: kotlinx.coroutines.flow.MutableStateFlow<Set<String>?>(null)
    }.collectAsState(initial = null)
    val documentScope = rememberCoroutineScope()

    val mangaSelActive by remember {
        mangaLibraryViewModel?.isSelectionMode ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }.collectAsState(initial = false)
    val mangaSelIds by remember {
        mangaLibraryViewModel?.selectedIds ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet())
    }.collectAsState(initial = emptySet<String>())
    val mangaActiveFilters by remember {
        mangaLibraryViewModel?.activeFilters
            ?: kotlinx.coroutines.flow.MutableStateFlow<Set<com.folio.reader.ui.manga.MangaLibFilter>>(emptySet())
    }.collectAsState(initial = emptySet<com.folio.reader.ui.manga.MangaLibFilter>())

    if (bookToDelete != null) {
        com.folio.reader.ui.components.ConfirmDialog(
            title = "Delete Book",
            message = "Are you sure you want to delete '${bookToDelete?.title}'? This will remove the book and its local files.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = { bookToDelete?.let { onDeleteBooks(setOf(it.id)) } },
            onDismiss = { bookToDelete = null }
        )
    }
    if (manageCollectionsOpen) {
        val manageScope = rememberCoroutineScope()
        BookCollectionManagerDialog(
            collections = shelfCollections,
            onCreate = { name -> manageScope.launch { viewModel.createCollection(name) } },
            onRename = { id, name -> viewModel.renameCollection(id, name) },
            onDelete = { id -> viewModel.deleteCollection(id) },
            onDismiss = { manageCollectionsOpen = false }
        )
    }
    if (documentToDelete != null) {
        com.folio.reader.ui.components.ConfirmDialog(
            title = "Delete document",
            message = "Delete '${documentToDelete?.title}' and its local files?",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                documentToDelete?.let(onDocumentDelete)
                documentToDelete = null
            },
            onDismiss = { documentToDelete = null }
        )
    }
    if (manageDocumentCategories) {
        DocumentCategoryManagerDialog(
            categories = documentCategories,
            onCreate = { name -> documentScope.launch { documentLibraryViewModel?.createCategory(name) } },
            onRename = { id, name -> documentLibraryViewModel?.renameCategory(id, name) },
            onDelete = { id -> documentLibraryViewModel?.deleteCategory(id) },
            onDismiss = { manageDocumentCategories = false }
        )
    }
    documentPicker?.let { (document, initial) ->
        DocumentCategoryPickerDialog(
            categories = documentCategories,
            initialSelected = initial,
            onCreate = { name -> documentLibraryViewModel?.createCategory(name) },
            onApply = { documentLibraryViewModel?.setCategoriesFor(document.id, it) },
            onDismiss = { documentPicker = null }
        )
    }
    documentBulkInitial?.let { initial ->
        DocumentCategoryPickerDialog(
            categories = documentCategories,
            initialSelected = initial,
            onCreate = { name -> documentLibraryViewModel?.createCategory(name) },
            onApply = { documentLibraryViewModel?.applyBulkCategories(it) },
            onDismiss = { documentLibraryViewModel?.closeBulkPicker() }
        )
    }

    // `remember`ed for the same reason as the shelf flows below: `allSeries()`
    // builds a *new* flow per call, and a flow whose identity changes on every
    // recomposition makes `collectAsState` tear down and resubscribe — a fresh
    // database read each time this screen recomposes.
    val allSeriesFlow = remember(viewModel) { viewModel.allSeries() }
    val allSeries by allSeriesFlow.collectAsState(initial = emptyList())
    bulkCollectionPickerInitial?.let { initial ->
        // The books counterpart of the dialog above: check set = each selected
        // book's complete collection membership after apply (§5.3's shelf rule).
        BookCollectionPickerDialog(
            collections = shelfCollections,
            initialSelected = initial,
            onCreate = { name -> viewModel.createCollection(name) },
            onApply = { viewModel.applyBulkCollections(it) },
            onDismiss = { viewModel.closeBulkPicker() }
        )
    }
    // The whole library, unfiltered: the rail's books search fans out over every
    // book, not just the ones the status/series chips are currently showing.
    //
    // The fallback is `remember`ed rather than called inline because
    // `allBooks()` returns a *new* flow per call: a flow rebuilt on every
    // recomposition makes `collectAsState` tear down and re-subscribe each time,
    // which is a fresh database read per recomposition of this screen.
    val knownBooksFlow: kotlinx.coroutines.flow.Flow<List<Book>> =
        knownBooks ?: remember(viewModel) { viewModel.allBooks() }
    val allBooks by knownBooksFlow.collectAsState(initial = emptyList())
    val railScope = rememberCoroutineScope()
    // §5.1: pace captions keyed by book id. Empty unless the host supplied a
    // session repository, so callers that don't want the extra read pay nothing.
    val finishEstimates by remember(viewModel) { viewModel.finishEstimates() }
        .collectAsState(initial = emptyMap())

    // `null` means "the shelf has not been measured yet" and is what the loading
    // branch keys off. It must not be the *initial* value on a warm entry — see
    // libraryShelfSeed, which is where the reasoning and the tests live.
    //
    // Sampled from the hoisted flow's *current* value rather than from
    // `allBooks` above: this is the argument to `collectAsState`, which reads it
    // once at subscription time, and it has to be right on the destination's
    // first frame. `allBooks` cannot be — `collectAsState` only delivers a
    // flow's value on the frame *after* the first composition, so seeding from
    // it would put one frame of skeleton inside the morph. See
    // [libraryShelfSeedFrom], which is where the reasoning and the tests live.
    val booksInitial: List<Book>? = remember(knownBooks) { libraryShelfSeedFrom(knownBooks) }
    // Keyed on the shelf's own inputs so the flow is only rebuilt when the shelf
    // actually changes, for the same reason as `knownBooksFlow` above.
    val shelfState = LibraryViewModel.LibraryState(
        viewMode = booksViewMode,
        sortBy = sortBy,
        sortAscending = sortAscending,
        filter = filter,
        selectedBookIds = selectedBooks,
        isSelectionMode = isSelectionMode
    )
    val shelfFlow = remember(viewModel, shelfState) { viewModel.filteredBooks(shelfState) }
    val books by shelfFlow.collectAsState(initial = booksInitial)

    val documentState by remember(documentLibraryViewModel) {
        documentLibraryViewModel?.state ?: kotlinx.coroutines.flow.MutableStateFlow(DocumentLibraryState())
    }.collectAsState()
    val mangaMode = libraryMode == LibraryMode.MANGA
    val documentMode = libraryMode == LibraryMode.DOCUMENTS

    // System back clears a bulk selection before it can do anything else. The
    // handlers are claimed here — inside the screen that shows the selection
    // bar — because the host-level handler only sees a back the nav stack
    // could not consume first, and a hold-selected shelf must never leak a
    // back press into navigation.
    com.folio.reader.ui.components.FolioBackHandler(
        enabled = !mangaMode && !documentMode && isSelectionMode
    ) { viewModel.clearSelection() }
    com.folio.reader.ui.components.FolioBackHandler(
        enabled = documentMode && documentSelectionActive
    ) { documentLibraryViewModel?.clearSelection() }
    com.folio.reader.ui.components.FolioBackHandler(
        enabled = mangaMode && mangaSelActive
    ) { mangaLibraryViewModel?.clearSelection() }
    // A back gesture with a search rail open closes the rail and returns to the shelf,
    // rather than navigating away from the Library. Claimed here — in the screen that
    // shows the rail — because the host's back walk evaluates `navController.popBackStack()`
    // before its own search-close branch, so a back reaching the host pops the Library
    // destination first. The most-recently-composed handler wins, so intercepting here
    // keeps the gesture from ever falling through to that pop. One per mode, matching the
    // mode whose rail is open.
    com.folio.reader.ui.components.FolioBackHandler(
        enabled = !mangaMode && !documentMode && bookSearchActive
    ) { onBookSearchActiveChange(false) }
    com.folio.reader.ui.components.FolioBackHandler(
        enabled = documentMode && documentSearchActive
    ) { onDocumentSearchActiveChange(false) }
    com.folio.reader.ui.components.FolioBackHandler(
        enabled = mangaMode && mangaSearchActive
    ) { onMangaSearchActiveChange(false) }

    // Warm the vector index when the book search rail opens so the first meaning/in-book query
    // does not pay for the ~60 MB load on the keystroke, and release it when the rail closes.
    // Mirrors the full-screen search's own preload/dispose. A no-op without a model or index.
    LaunchedEffect(bookSearchActive, bookSearchController) {
        val controller = bookSearchController ?: return@LaunchedEffect
        // Warm on open, but do NOT release the index on close: the int8 index is small (~38 MB for
        // the whole library, far less when scoped to a book) and releasing it on every close made
        // the next search rebuild from SQLite — the "results took way longer than they should"
        // report. Keeping it resident means only a scope change (book ↔ library) rebuilds.
        //
        // The embedder session IS released on close, though: it is 40-60 MB of native memory that
        // would otherwise sit resident in the background (the multilingual model far more) — the
        // footprint the lowmemorykiller targets. It reopens on the next query, cached for that
        // search session. Index fast, session lean: the two have opposite lifetimes on purpose.
        if (bookSearchActive) runCatching { controller.preload() }
        else runCatching { controller.releaseEmbedder() }
    }
    // The masthead collapses off whatever the shelf below it consumed, so the grid
    // dissolves into the bar the way Home's hero does instead of sliding under a
    // fixed slab of chrome.
    val headerState = com.folio.reader.ui.components.rememberFolioHeaderState()
    // The collapse offset is a `rememberSaveable` accumulator, so returning to Library from Home
    // restores whatever value it held — and `FolioTopBar` multiplies the rail's height/alpha by
    // (1 - collapse), so a restored-collapsed bar comes back folded to zero height over reserved
    // inset: the "search bar is blank space until I scroll" report. It only re-synced on the next
    // scroll event. Expanding on (re)entry, and whenever the search rail opens, keeps the bar
    // present. The masthead title rides the same collapse, which is why the old blanket per-mode
    // reset was removed; keying this on entry/search-open (not every recomposition) avoids the
    // title snapping around under a mode switch.
    LaunchedEffect(Unit) { headerState.reset() }
    LaunchedEffect(bookSearchActive) { if (bookSearchActive) headerState.reset() }
    // The collapse is a sticky accumulator shared by all three shelves. v1.2.11
    // reset it on every mode switch so an incoming shelf could not arrive with its
    // rail folded under the bar — but the collapse also drives the masthead title's
    // scale (FolioChrome.FolioBarTitle scales 1.0 → 0.84), so that blanket reset
    // snapped the title back to full size on every switch and left it there: the
    // "Documents changed the masthead and it persisted back into Books/Manga"
    // report. The masthead now stays where the reader put it and simply follows
    // the shelf's own scroll, so the type never resizes under a mode switch. The
    // rail stays reachable because the switch rides inside each mode's own rail.
    // A green tick with a red "1" on it was the loudest object in the bar and said
    // nothing a reader can act on. The badge now appears only when sync actually
    // wants attention.
    val syncNeedsAttention = syncState != null && syncState.isConfigured && (
        syncState.isSyncing ||
            syncState.lastError != null ||
            syncState.quotaLimited ||
            (syncState.pendingUploadCount + syncState.pendingDownloadCount) > 0
        )

    // The rail: the Books/Manga/Documents switch, then that mode's filters, on one
    // scrollable row. Built here so the masthead can fold it away as a unit.
    // Measured on the content, not the folding box the masthead wraps it in — that
    // one reports a shrinking height as the shelf scrolls, by design.
    //
    // Cached per mode rather than one shared value, because a shared value survives
    // the switch by exactly the one layout pass that matters: the incoming shelf is
    // measured under the *outgoing* mode's rail height and jumps when the new rail
    // reports its own. That hop was the reported "layout changes for a split second".
    //
    // Only the *filter* rail's height is recorded here. The search rail is measured too —
    // the same `Box.onSizeChanged` wraps both — but its reading is deliberately dropped:
    // see [shelfInset] for why the shelf must not move when a search field opens over it.
    val railPxByMode = remember { mutableStateMapOf<LibraryMode, Int>() }
    // The search rail's real height, measured while a book search is open. `railPxByMode`
    // deliberately freezes to the *filter* rail's height so the grid does not slide when the
    // field opens over it (see [shelfInset]); but the search results list replaces the shelf and
    // sits under this taller rail, so it needs the real height as its top inset or the masthead
    // overlaps its first rows.
    var searchRailPx by remember { mutableStateOf(0) }
    // The last measured (non-search) rail height, persisted across tab switches. `railPxByMode` is
    // a plain `remember`, so it resets to empty every time the Library destination re-composes on
    // re-entry from Home/Stats; the shelf inset then falls back to 0 for one frame and the whole
    // grid — masthead rail included — visibly jumps down once the rail measures itself. Seeding the
    // fallback from a `rememberSaveable` (which survives the nav save/restore) removes that jump.
    var lastRailPx by rememberSaveable { mutableStateOf(0) }
    val railContent: (@Composable () -> Unit)? = when (libraryMode) {
        LibraryMode.MANGA -> mangaLibraryViewModel?.let { mangaVm -> ({
            // Manga's chrome, in the same slot the other two modes use. It used to
            // be a row pinned inside the shelf, which is why entering Manga changed
            // the header's structure and the incoming grid had to be re-laid out.
            // Same gate as the Books rail: the search field is taller than the chips row it
            // replaces, and letting that height reach the shelf offset slides the shelf down
            // when search opens. See [shelfInset].
            Box(
                Modifier.onSizeChanged {
                    if (!mangaSearchActive) { railPxByMode[libraryMode] = it.height; lastRailPx = it.height }
                }
            ) {
                MangaLibraryRail(
                    viewModel = mangaVm,
                    searchActive = mangaSearchActive,
                    onSearchActiveChange = onMangaSearchActiveChange,
                    sourcesAvailable = mangaSourcesAvailable,
                    onOpenDownloads = onOpenMangaDownloads,
                    leading = { LibraryModeSwitch(libraryMode, onLibraryModeChange) },
                )
            }
        }) }
        LibraryMode.DOCUMENTS -> ({
            // One rail, exactly like Books and Manga: the switch leads the
            // category chips, so no mode stacks its categories below the
            // selector. While searching, the chips give way to the field but
            // the switch stays at the head of the row.
            // Same gate again. The document rail keeps the switch at the head of the row while
            // searching, so its height changes less than the Book rail's — but it still
            // changes, and the shelf must not move. See [shelfInset].
            Box(
                Modifier.onSizeChanged {
                    if (!documentSearchActive) { railPxByMode[libraryMode] = it.height; lastRailPx = it.height }
                }
            ) {
                if (documentSearchActive && documentLibraryViewModel != null) {
                    LibrarySearchRail(
                        switch = { LibraryModeSwitch(libraryMode, onLibraryModeChange) },
                        query = documentState.query,
                        onQueryChange = { documentLibraryViewModel?.setQuery(it) },
                        placeholder = "Search documents",
                        onClose = {
                            // The document grid's query flow filters as long as the
                            // text is non-blank, so closing the field clears it —
                            // otherwise the shelf would stay filtered with no field
                            // on screen to explain why.
                            documentLibraryViewModel?.setQuery("")
                            onDocumentSearchActiveChange(false)
                        },
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = FolioTokens.gutter),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item { LibraryModeSwitch(libraryMode, onLibraryModeChange) }
                        items(documentCategories, key = { it.id }) { category ->
                            com.folio.reader.ui.components.FolioChip(
                                selected = selectedDocumentCategory == category.id,
                                onClick = { documentLibraryViewModel?.selectCategory(category.id) },
                                label = category.name
                            )
                        }
                        item {
                            com.folio.reader.ui.components.FolioChip(
                                selected = false,
                                onClick = { manageDocumentCategories = true },
                                label = "Edit"
                            )
                        }
                    }
                }
            }
        })
        LibraryMode.BOOKS -> ({
            // Only the filter rail's height is recorded. `onSizeChanged` fires for whichever
            // branch is composed, so gating on `!bookSearchActive` is what keeps the taller
            // search rail out of the shelf's offset — see [shelfInset]. Without the gate the
            // measurement is overwritten the moment search opens and the whole grid slides
            // down by the difference.
            Box(
                Modifier.onSizeChanged {
                    if (!bookSearchActive) { railPxByMode[libraryMode] = it.height; lastRailPx = it.height }
                    else searchRailPx = it.height
                }
            ) {
                val controller = bookSearchController
                if (bookSearchActive && controller != null) {
                    Column(Modifier.fillMaxWidth()) {
                        LibrarySearchRail(
                            switch = { LibraryModeSwitch(libraryMode, onLibraryModeChange) },
                            query = controller.query,
                            onQueryChange = { q ->
                                controller.runSearch(q, controller.scope, allBooks, railScope)
                            },
                            placeholder = "Search books",
                            onClose = { onBookSearchActiveChange(false) },
                            // The scope selector lives on the field's own search icon:
                            // a chip row below the field folded out of sight on phones,
                            // which is how Titles/Content/Highlights/Notes ended up
                            // undiscoverable. The dropdown cannot scroll away.
                            scopeOptions = SearchScope.entries.map { it.label },
                            scopeSelected = SearchScope.entries.indexOf(controller.scope).coerceAtLeast(0),
                            onScopeSelect = { index ->
                                controller.runSearch(
                                    controller.query,
                                    SearchScope.entries[index],
                                    allBooks,
                                    railScope,
                                )
                            },
                        )
                        // Retrieval mode, exactly as the reader's search screen offers it: the
                        // same three chips, under the same two conditions — Content scope only,
                        // and only on a build that can do semantics at all.
                        //
                        // Content-only is not a simplification, it is the same rule the other
                        // surface has and for the same reason: Titles/Highlights/Notes/Bookmarks
                        // are exact-match lookups over short strings, so an embedding adds
                        // nothing, and showing "Meaning" over a title list would promise
                        // something the index cannot deliver. Semantics *are* meaningful here
                        // because this is the field that searches inside books — the library
                        // rail is the road most readers take to content search, and until now
                        // it led to a search that had no mode at all.
                        if (controller.scope == SearchScope.CONTENT && controller.semanticAvailable) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = FolioTokens.gutter,
                                    vertical = 2.dp,
                                ),
                            ) {
                                items(SearchMode.entries.toList(), key = { "railmode:${it.name}" }) { m ->
                                    com.folio.reader.ui.components.FolioChip(
                                        selected = controller.mode == m,
                                        onClick = {
                                            controller.mode = m
                                            controller.runSearch(
                                                controller.query,
                                                controller.scope,
                                                allBooks,
                                                railScope,
                                            )
                                        },
                                        label = m.label,
                                    )
                                }
                            }
                            if (controller.semanticUnavailable) {
                                Text(
                                    text = "Semantic index not built yet — showing exact matches. " +
                                        "Download the model in Settings, then index your library.",
                                    modifier = Modifier.padding(horizontal = FolioTokens.gutter, vertical = 2.dp),
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    LibraryFilterChips(
                        filter = filter,
                        allSeries = allSeries,
                        onFilterChange = { filter = it },
                        collections = shelfCollections,
                        selectedCollectionId = selectedCollectionId,
                        onSelectCollection = { viewModel.selectCollection(it) },
                        onEditCollections = { manageCollectionsOpen = true },
                        leading = if (mangaContent != null || documentLibraryViewModel != null) {
                            ({ LibraryModeSwitch(libraryMode, onLibraryModeChange) })
                        } else {
                            null
                        },
                    )
                }
            }
        })
    }

    // Published rather than imposed, exactly like LocalFolioBarInset at the bottom
    // edge: the shelves add it to their own contentPadding so their first row clears
    // the glass while everything past it scrolls underneath. It is a function of the
    // mode rather than one value, because a swap has two layers alive at once and the
    // one still fading out must keep *its own* rail's height — hand it the newly
    // selected mode's and it re-pads and slides under the incoming chrome, which is
    // the flash at the start of a switch. The fallback to any measured rail is exact
    // here: all three rails are one row of the same chips.
    val shelfDensity = LocalDensity.current
    // ### The rail height, and why the search field's is not part of it
    //
    // The rail genuinely is taller while searching: on a phone the field and the
    // Books/Manga/Documents switch stack into two rows instead of one. Feeding that taller
    // number into the shelf's top padding slid the whole grid down by the difference the
    // instant search was opened. Measured on the device — switch row y 269px → 407px, every
    // cover following at +156px — the row that had been fully visible was cut off at the
    // bottom edge and the shelf below the chrome read as **blank**. That is the report
    // "tapping the Library search icon blanks the book grid", and it is a layout shift, not
    // data loss: all eight books were present the whole time, just pushed under the fold.
    // (It has been misdiagnosed once as an empty library, which is why the numbers above are
    // recorded here rather than left as a claim.)
    //
    // Keeping it out is also the right behaviour on its own terms. The search field is chrome
    // the reader opened *over* the shelf; the shelf is not a new page and has no reason to
    // move when a field appears. Holding the non-searching height means the grid stays exactly
    // where it was and the field overlays it — what the reader asked for: *"things should stay
    // when search is tapped and it's empty."*
    //
    // Enforced at the measurement site (each rail's `onSizeChanged` writes only when its own
    // search is inactive), so there is one place per mode that decides this and no second
    // map to keep in sync.
    val shelfInset: @Composable (LibraryMode) -> Dp = { mode ->
        folioBarTopInset(
            with(shelfDensity) {
                // The fallback to any measured rail stays: it covers the first frame, before
                // this mode's filter rail has reported a height. Every rail is at least one
                // row of the same chips and the switch, so a close approximation for one frame
                // beats a shelf that starts at the top edge and then jumps down.
                (railPxByMode[mode] ?: railPxByMode.values.firstOrNull() ?: lastRailPx).toDp()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            documentMode && documentSelectionActive -> {
                com.folio.reader.ui.components.FolioTopBar(
                    title = "${selectedDocumentIds.size} selected",
                    collapse = headerState.collapse,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                    navigationIcon = {
                        IconButton(onClick = { documentLibraryViewModel?.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            onShareDocuments(selectedDocumentIds)
                            documentLibraryViewModel?.clearSelection()
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share files")
                        }
                        IconButton(onClick = { documentLibraryViewModel?.requestBulkCategories() }) {
                            Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Set categories")
                        }
                    },
                    rail = railContent
                )
            }
            mangaMode && mangaSelActive -> {
                // Selection mode swaps the regular chrome for bulk actions in the same
                // bar — no extra block, no layout shift below.
                com.folio.reader.ui.components.FolioTopBar(
                    title = "${mangaSelIds.size} selected",
                    collapse = headerState.collapse,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                    bottomRule = false,
                    navigationIcon = {
                        IconButton(onClick = { mangaLibraryViewModel?.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { mangaLibraryViewModel?.requestBulkCategories() }) {
                            Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Set categories")
                        }
                        IconButton(onClick = { mangaLibraryViewModel?.markSelectedRead(true) }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Mark read")
                        }
                        IconButton(onClick = { mangaLibraryViewModel?.markSelectedRead(false) }) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Mark unread")
                        }
                        IconButton(onClick = {
                            onRemoveSelectedManga?.invoke(mangaSelIds)
                                ?: mangaLibraryViewModel?.removeSelected()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove from library", tint = FolioTheme.colors.error)
                        }
                    },
                )
            }
            !mangaMode && !documentMode && isSelectionMode -> {
                // Books bulk-selection swaps the same bar, so the tab row below never moves.
                com.folio.reader.ui.components.FolioTopBar(
                    title = "${selectedBooks.size} selected",
                    collapse = headerState.collapse,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        // Add to collections first: it is the reason selection
                        // exists on a shelf — the same Label action the manga and
                        // document selection bars lead with.
                        IconButton(onClick = { viewModel.requestBulkCollections() }) {
                            Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Add to collections")
                        }
                        IconButton(onClick = {
                            onShareBooks(selectedBooks)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share files")
                        }
                        IconButton(onClick = {
                            onSetBookStatus(selectedBooks, BookStatus.READING)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Mark as reading")
                        }
                        IconButton(onClick = {
                            onSetBookStatus(selectedBooks, BookStatus.FINISHED)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Mark as finished")
                        }
                        IconButton(onClick = {
                            onDeleteBooks(selectedBooks)
                            viewModel.clearSelection()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete books", tint = FolioTheme.colors.error)
                        }
                    },
                    rail = railContent
                )
            }
            else -> {
            com.folio.reader.ui.components.FolioTopBar(
                // The screen's own name. "Folio" belongs on Home, where the mark and
                // the wordmark form the masthead; repeating it here left the library
                // as the only top-level screen that never said what it was.
                title = "Library",
                collapse = headerState.collapse,
                modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                actions = {
                    if (syncState != null && syncNeedsAttention) {
                        com.folio.reader.ui.components.SyncStatusBadge(
                            syncState = syncState,
                            onClick = onSyncNow
                        )
                    }
                    // One search contract for every shelf: the icon toggles the
                    // mode's rail search (manga routes through its own callback).
                    // Documents used to disable this icon because their field was
                    // permanently on the rail; the unified search gives them the
                    // same toggle the other two modes have.
                    IconButton(onClick = {
                        when (libraryMode) {
                            LibraryMode.MANGA -> onMangaSearchClick()
                            else -> onSearchClick()
                        }
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = {
                        when (libraryMode) {
                            LibraryMode.BOOKS -> onImportClick()
                            LibraryMode.DOCUMENTS -> onDocumentImportClick()
                            LibraryMode.MANGA -> onMangaImportClick()
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Import")
                    }
                    // Settings moved into the overflow. Five action slots plus the mark
                    // left the masthead ~80dp for its own name, which is how "Library"
                    // became "Lib…"; a destination belongs in a menu, not on the rail.
                    // Display: how the shelf is drawn and ordered. Splitting this out
                    // of the overflow is what got both menus back to a readable length.
                    Box {
                        IconButton(onClick = {
                            when (libraryMode) {
                                LibraryMode.BOOKS, LibraryMode.DOCUMENTS -> displayOpen = true
                                LibraryMode.MANGA -> mangaDisplayOpen = true
                            }
                        }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Display and sort")
                        }
                        when (libraryMode) {
                            LibraryMode.MANGA -> {
                            DropdownMenu(expanded = mangaDisplayOpen, onDismissRequest = { mangaDisplayOpen = false }) {
                                com.folio.reader.ui.components.FolioMenuLabel("View")
                                ViewModeRow(
                                    selected = mangaViewMode.ordinal,
                                    onSelect = { index ->
                                        onMangaViewModeChange(com.folio.reader.ui.manga.MangaViewMode.entries[index])
                                    },
                                )
                                com.folio.reader.ui.components.FolioMenuLabel("Sort")
                                com.folio.reader.ui.manga.MangaSortBy.entries.forEach { o ->
                                    val active = mangaLibraryViewModel?.sortBy?.value == o
                                    DropdownMenuItem(
                                        text = {
                                            Text(o.label, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface)
                                        },
                                        leadingIcon = { ViewCheck(active) },
                                        onClick = {
                                            mangaDisplayOpen = false
                                            mangaLibraryViewModel?.sortBy?.value = o
                                        },
                                    )
                                }
                                com.folio.reader.ui.components.FolioMenuLabel("Filter")
                                com.folio.reader.ui.manga.MangaLibFilter.entries.forEach { f ->
                                    val active = f in mangaActiveFilters
                                    DropdownMenuItem(
                                        text = {
                                            Text(f.label, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface)
                                        },
                                        leadingIcon = { ViewCheck(active) },
                                        // Menu stays open so several filters can be combined in one go.
                                        onClick = { mangaLibraryViewModel?.toggleFilter(f) },
                                    )
                                }
                            }
                            }
                            LibraryMode.DOCUMENTS -> {
                                DropdownMenu(
                                    expanded = displayOpen,
                                    onDismissRequest = { displayOpen = false },
                                    modifier = Modifier.heightIn(max = 420.dp)
                                ) {
                                    com.folio.reader.ui.components.FolioMenuLabel("View")
                                    ViewModeRow(
                                        selected = documentState.viewMode.ordinal,
                                        optionCount = DocumentViewMode.entries.size,
                                        onSelect = { index -> documentLibraryViewModel?.setViewMode(DocumentViewMode.entries[index]) },
                                    )
                                    com.folio.reader.ui.components.FolioMenuLabel("Sort")
                                    DocumentSortBy.entries.forEach { option ->
                                        val active = option == documentState.sortBy
                                        DropdownMenuItem(
                                            text = { Text(option.label, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface) },
                                            leadingIcon = { ViewCheck(active) },
                                            onClick = { displayOpen = false; documentLibraryViewModel?.setSort(option) },
                                        )
                                    }
                                    HorizontalDivider()
                                    com.folio.reader.ui.components.FolioMenuLabel("Format")
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "All formats",
                                                color = if (documentState.formatFilter == null) FolioTheme.colors.primary else FolioTheme.colors.onSurface
                                            )
                                        },
                                        leadingIcon = { ViewCheck(documentState.formatFilter == null) },
                                        onClick = { documentLibraryViewModel?.setFormatFilter(null) }
                                    )
                                    DocumentFormat.entries.forEach { format ->
                                        val active = format == documentState.formatFilter
                                        DropdownMenuItem(
                                            text = { Text(format.name, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface) },
                                            leadingIcon = { ViewCheck(active) },
                                            onClick = { documentLibraryViewModel?.setFormatFilter(format) }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (documentState.sortAscending) "Ascending" else "Descending") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (documentState.sortAscending) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = FolioTheme.colors.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = { displayOpen = false; documentLibraryViewModel?.toggleSortDirection() },
                                    )
                                }
                            }
                            LibraryMode.BOOKS -> {
                            DropdownMenu(expanded = displayOpen, onDismissRequest = { displayOpen = false }) {
                                val bookFriendlyNames = linkedMapOf(
                                    LibraryViewModel.SortBy.LAST_OPENED to "Recently opened",
                                    LibraryViewModel.SortBy.DATE_ADDED to "Date added",
                                    LibraryViewModel.SortBy.TITLE to "Title",
                                    LibraryViewModel.SortBy.AUTHOR to "Author",
                                    LibraryViewModel.SortBy.PROGRESS to "Progress",
                                    LibraryViewModel.SortBy.READING_TIME to "Length",
                                    LibraryViewModel.SortBy.COMPLETION_DATE to "Completion date",
                                    LibraryViewModel.SortBy.FILE_SIZE to "File size",
                                )
                                com.folio.reader.ui.components.FolioMenuLabel("View")
                                ViewModeRow(
                                    selected = booksViewMode.ordinal,
                                    onSelect = { index ->
                                        onBooksViewModeChange(LibraryViewModel.ViewMode.entries[index])
                                    },
                                )
                                com.folio.reader.ui.components.FolioMenuLabel("Sort")
                                bookFriendlyNames.forEach { (option, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                color = if (option == sortBy) FolioTheme.colors.primary else FolioTheme.colors.onSurface,
                                            )
                                        },
                                        leadingIcon = { ViewCheck(option == sortBy) },
                                        onClick = { displayOpen = false; sortBy = option },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (sortAscending) "Ascending" else "Descending") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (sortAscending) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = FolioTheme.colors.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    onClick = { displayOpen = false; sortAscending = !sortAscending },
                                )
                            }
                        }
                    }
                    }
                    // Overflow: destinations and one-off actions only.
                    Box {
                        if (!documentMode || showSettingsAction) {
                            IconButton(onClick = { if (mangaMode) mangaOverflowOpen = true else overflowOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                        } else {
                            // The kebab's slot is reserved even when its menu is
                            // empty (Android documents, whose only entry would be
                            // Settings): without it the title box widens by one
                            // icon, "Library" steps back up the type ladder, and
                            // the documents masthead reads as a different screen
                            // from the books and manga ones.
                            Spacer(Modifier.width(48.dp))
                        }
                        if (mangaMode) {
                            DropdownMenu(expanded = mangaOverflowOpen, onDismissRequest = { mangaOverflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Browse sources") },
                                    onClick = { mangaOverflowOpen = false; onMangaBrowseClick() },
                                )
                                // Downloads live on the manga rail (with a live queue
                                // count) — the overflow item was redundant.
                                DropdownMenuItem(
                                    text = { Text("Reading history") },
                                    onClick = { mangaOverflowOpen = false; onMangaHistoryClick() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Check for new chapters") },
                                    onClick = { mangaOverflowOpen = false; mangaLibraryViewModel?.updateLibrary() },
                                )
                                if (mangaExtensionsAvailable) {
                                    DropdownMenuItem(
                                        text = { Text("Extensions") },
                                        onClick = { mangaOverflowOpen = false; onMangaExtensionsClick() },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Import backup") },
                                    onClick = { mangaOverflowOpen = false; onMangaBackupImport() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export backup") },
                                    onClick = { mangaOverflowOpen = false; onMangaBackupExport() },
                                )
                                if (showSettingsAction) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = { mangaOverflowOpen = false; onSettingsClick() },
                                    )
                                }
                            }
                        } else if (documentMode) {
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                if (showSettingsAction) {
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = { overflowOpen = false; onSettingsClick() },
                                    )
                                }
                            }
                        } else {
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Tags") },
                                    onClick = { overflowOpen = false; onTagManagerClick() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Revisit Items") },
                                    onClick = { overflowOpen = false; onRevisitClick() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Quotes") },
                                    onClick = { overflowOpen = false; onQuoteBrowserClick() }
                                )
                                // Desktop reaches Stats from here; the Android host has
                                // it in the nav capsule and passes null.
                                if (onOpenStats != null) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Stats") },
                                        onClick = { overflowOpen = false; onOpenStats() }
                                    )
                                }
                                if (showSettingsAction) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = { overflowOpen = false; onSettingsClick() },
                                    )
                                }
                            }
                        }
                    }
                },
                // One rail instead of three stacked rows. The Library mode switch
                // leads the same scrollable row the filters live on, and the whole
                // row folds up under the bar as the shelf scrolls. All three modes
                // — manga included, since §17 — put their chrome here, so a switch
                // never changes the header's structure underneath the shelf.
                rail = railContent,
            )
            }
        }

        // The shelf passes its scroll up to the masthead through nested scroll, so no
        // grid or list had to hoist its own state to get the collapse. It now runs
        // full-bleed to the top of the window with the masthead floating over it —
        // the only arrangement in which there is anything behind the glass to see.
        //
        // A short dissolve between the shelves. The hard cut read as a jump, and the
        // slide + scale + SizeTransform the §17 pass put in its place read worse: the
        // chrome above the shelf is *shared*, so a swap has to leave the page exactly
        // where it is. A slide uncovers a bare band at the page edge, a scale softens
        // every cover and glyph for the whole cross, and SizeTransform measures the
        // incoming grid against an interpolated width, which changes its column count
        // and then snaps it back — the "layout changes for a split second" report.
        // Scroll and selection ride the saveable state holder, so each shelf returns
        // exactly where it was; only the dissolve is animated.
        val shelfStateHolder = rememberSaveableStateHolder()
        val swapMotion = rememberMotionEnabled()
        // SizeTransform is opt-in per swap and gated on the two shelves' item
        // counts: the re-column failure above is a function of how many rows the
        // incoming grid has to measure, so a swap between two small shelves
        // (a handful of documents, a couple of manga) gets the smoothness and a
        // swap involving a full shelf keeps the plain dissolve. maxOf because it
        // is the taller side that decides the row count.
        val shelfItemCount = maxOf(
            books?.size ?: 0,
            documentState.items.size,
        )
        CompositionLocalProvider(LocalFolioTopInset provides shelfInset(libraryMode)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(headerState.nestedScrollConnection)
                    // §16: the shelf (books grid, documents, manga, or the stats
                    // hub when embedded here) is the backdrop the masthead and
                    // capsule blur. One box, one source — never the grids
                    // themselves, so a swap dissolves inside a single registered
                    // backdrop instead of registering two.
                    .folioBackdropSource(),
            ) {
                AnimatedContent(
                    targetState = libraryMode,
                    transitionSpec = {
                        folioFadeSwap(
                            swapMotion,
                            sizeTransform = folioSwapSizeTransform()
                                .takeIf { folioSizeTransformEligible(shelfItemCount) },
                        )
                    },
                    label = "shelf swap",
                ) { mode ->
                    CompositionLocalProvider(LocalFolioTopInset provides shelfInset(mode)) {
                        // Only ever render the shelf whose mode matches the *current* selection.
                        // AnimatedContent keeps the outgoing mode composed and visible through the
                        // cross-fade, while the masthead rail switches to the new mode immediately —
                        // so without this guard the new mode's rail is painted over the previous
                        // mode's grid (e.g. a manga shelf under the Books collection row, briefly
                        // exposing another tab's — possibly private — collection). Blanking the
                        // outgoing child closes that leak; the incoming shelf still fades in.
                        if (mode != libraryMode) {
                            Box(Modifier.fillMaxSize())
                        } else when (mode) {
                            LibraryMode.MANGA -> shelfStateHolder.SaveableStateProvider("manga") {
                                mangaContent?.invoke()
                            }
                            LibraryMode.DOCUMENTS -> shelfStateHolder.SaveableStateProvider("documents") {
                                DocumentLibraryContent(
                                    state = documentState,
                                    selectedIds = selectedDocumentIds,
                                    isSelectionMode = documentSelectionActive,
                                    onImport = onDocumentImportClick,
                                    onOpen = onDocumentOpen,
                                    onDelete = { documentToDelete = it },
                                    onCategories = { document ->
                                        documentScope.launch {
                                            val initial = documentLibraryViewModel
                                                ?.categoriesFor(document.id)
                                                ?: emptySet()
                                            documentPicker = document to initial
                                        }
                                    },
                                    onToggleSelection = {
                                        documentLibraryViewModel?.toggleSelection(it)
                                    }
                                )
                            }
                            LibraryMode.BOOKS -> shelfStateHolder.SaveableStateProvider("books") {
                                // Searching the shelf: the Titles scope filters the grid in
                                // place; every other scope replaces the shelf with the same
                                // hit list the full-screen search renders, so one interaction
                                // covers both surfaces.
                                val controller = bookSearchController
                                if (bookSearchActive && controller != null && controller.scope != SearchScope.TITLES) {
                                    // This list sits under the *search* rail, which is taller than
                                    // the filter rail `shelfInset` (the grid's inset) is frozen to.
                                    // Override the top inset with the search rail's measured height
                                    // so the masthead does not cover the first results.
                                    val searchInset = folioBarTopInset(
                                        with(shelfDensity) {
                                            (if (searchRailPx > 0) searchRailPx
                                            else railPxByMode[LibraryMode.BOOKS] ?: 0).toDp()
                                        }
                                    )
                                    CompositionLocalProvider(LocalFolioTopInset provides searchInset) {
                                        Box(Modifier.fillMaxSize()) {
                                            BookSearchResultsList(
                                                query = controller.query,
                                                scope = controller.scope,
                                                titleMatches = controller.titleMatches,
                                                results = controller.results,
                                                annotationResults = controller.annotationResults,
                                                // The rail already explains a floor-rejected result
                                                // above the list; the list must not contradict it with
                                                // "No matches", which says something else about the
                                                // same empty answer.
                                                 noStrongMatch = controller.noStrongMatch,
                                                 searching = controller.searching,
                                                 onOpenTitle = onBookDetailClick,
                                                 onOpenHit = onOpenBookHit,
                                                 modifier = Modifier.fillMaxSize(),
                                             )
                                            // Indeterminate line while a content/annotation search
                                            // runs, so a slow query reads as "working", not "no
                                            // results". Offset by `searchInset` so it sits *below*
                                            // the floating glass masthead — pinned to TopCenter with
                                            // no offset it rendered behind the bar and was invisible,
                                            // which is why the indicator never appeared.
                                            if (controller.searching) {
                                                androidx.compose.material3.LinearProgressIndicator(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .align(Alignment.TopCenter)
                                                        .padding(top = searchInset),
                                                    color = FolioTheme.colors.accentProgress,
                                                    trackColor = FolioTheme.colors.accentProgress.copy(alpha = 0.18f),
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    val queryText = if (bookSearchActive) controller?.query?.trim().orEmpty() else ""
                                    val displayed = if (queryText.isNotEmpty()) {
                                        books?.filter {
                                            it.title.contains(queryText, ignoreCase = true) ||
                                                it.displayAuthor.contains(queryText, ignoreCase = true)
                                        }
                                    } else {
                                        books
                                    }
                                    if (displayed != null && displayed.isEmpty() && queryText.isNotEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            com.folio.reader.ui.components.EmptyState(
                                                icon = Icons.Filled.Search,
                                                headline = "No matches for \"$queryText\"",
                                            )
                                        }
                                    } else {
                                        LibraryContent(
                                            books = displayed,
                                            libraryEmpty = allBooks.isEmpty(),
                                            viewMode = booksViewMode,
                                            selectedBooks = selectedBooks,
                                            isSelectionMode = isSelectionMode,
                                            preserveFeaturedDuringSelection = preserveFeaturedBookDuringSelection,
                                            finishEstimates = finishEstimates,
                                            onBookClick = {
                                                if (isSelectionMode) viewModel.toggleSelection(it.id)
                                                else onBookDetailClick(it)
                                            },
                                            onBookLongClick = { viewModel.toggleSelection(it.id) },
                                            onDeleteBook = { bookToDelete = it },
                                            onImportClick = onImportClick
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The books collection editor — the same manage dialog the manga and documents
 * shelves open from their rail's Edit chip: create inline, rename in place,
 * delete (Main only while another collection exists, so the library never
 * loses its last shelf).
 */
@Composable
private fun BookCollectionManagerDialog(
    collections: List<FolioCollection>,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newCollection by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCollection,
                        onValueChange = { newCollection = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("New collection") },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            val name = newCollection.trim()
                            if (name.isNotEmpty()) {
                                onCreate(name)
                                newCollection = ""
                            }
                        },
                        enabled = newCollection.isNotBlank()
                    ) {
                        Text("Add")
                    }
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(collections, key = { it.id }) { collection ->
                        BookCollectionRow(
                            collection = collection,
                            canDelete = collection.id != FolioCollection.MAIN_ID || collections.size > 1,
                            onRename = { onRename(collection.id, it) },
                            onDelete = { onDelete(collection.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun BookCollectionRow(
    collection: FolioCollection,
    canDelete: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember(collection.id) { mutableStateOf(false) }
    var name by remember(collection.id, collection.name) { mutableStateOf(collection.name) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onRename(name.trim())
                    editing = false
                }
            ) {
                Text("Save")
            }
        } else {
            Text(
                collection.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { editing = true }) {
                Text("Rename")
            }
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (canDelete) {
                        "Remove"
                    } else {
                        "Create another collection before removing this one"
                    }
                )
            }
        }
    }
}

@Composable
private fun DocumentCategoryManagerDialog(
    categories: List<DocumentCategory>,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newCategory by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("New category") },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            val name = newCategory.trim()
                            if (name.isNotEmpty()) {
                                onCreate(name)
                                newCategory = ""
                            }
                        },
                        enabled = newCategory.isNotBlank()
                    ) {
                        Text("Add")
                    }
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories, key = { it.id }) { category ->
                        DocumentCategoryRow(
                            category = category,
                            canDelete = category.id != DocumentCategory.MAIN_ID || categories.size > 1,
                            onRename = { onRename(category.id, it) },
                            onDelete = { onDelete(category.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun DocumentCategoryRow(
    category: DocumentCategory,
    canDelete: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember(category.id) { mutableStateOf(false) }
    var name by remember(category.id, category.name) { mutableStateOf(category.name) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onRename(name.trim())
                    editing = false
                }
            ) {
                Text("Save")
            }
        } else {
            Text(
                category.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { editing = true }) {
                Text("Rename")
            }
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (canDelete) {
                        "Remove"
                    } else {
                        "Create another category before removing this one"
                    }
                )
            }
        }
    }
}

@Composable
private fun DocumentCategoryPickerDialog(
    categories: List<DocumentCategory>,
    initialSelected: Set<String>,
    onCreate: suspend (String) -> String?,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
    var newName by remember { mutableStateOf("") }

    fun apply(next: Set<String>) {
        selected = next
        onApply(next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (categories.isEmpty()) {
                    Text(
                        "No categories yet — create one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                apply(
                                    if (category.id in selected) {
                                        selected - category.id
                                    } else {
                                        selected + category.id
                                    }
                                )
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = category.id in selected,
                            onCheckedChange = { checked ->
                                apply(
                                    if (checked) selected + category.id
                                    else selected - category.id
                                )
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            category.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New category") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val name = newName.trim()
                            if (name.isNotEmpty()) {
                                scope.launch {
                                    onCreate(name)?.let { apply(selected + it) }
                                }
                                newName = ""
                            }
                        },
                        enabled = newName.trim().isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create category")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/**
 * Bulk collections for the selected books — the books counterpart of
 * [DocumentCategoryPickerDialog] and the manga picker, with the same contract:
 * taps apply immediately (no confirm step), the check set *is* each selected
 * book's membership after apply, and a collection can be created inline and
 * checked in one move so a brand-new shelf can be filled without leaving the
 * dialog.
 */
@Composable
private fun BookCollectionPickerDialog(
    collections: List<FolioCollection>,
    initialSelected: Set<String>,
    onCreate: suspend (String) -> String?,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
    var newName by remember { mutableStateOf("") }

    fun apply(next: Set<String>) {
        selected = next
        onApply(next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (collections.isEmpty()) {
                    Text(
                        "No collections yet — create one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
                collections.forEach { collection ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                apply(
                                    if (collection.id in selected) selected - collection.id
                                    else selected + collection.id
                                )
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = collection.id in selected,
                            onCheckedChange = { checked ->
                                apply(
                                    if (checked) selected + collection.id
                                    else selected - collection.id
                                )
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            collection.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New collection") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val name = newName.trim()
                            if (name.isNotEmpty()) {
                                scope.launch {
                                    onCreate(name)?.let { apply(selected + it) }
                                }
                                newName = ""
                            }
                        },
                        enabled = newName.trim().isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create collection")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun DocumentLibraryContent(
    state: DocumentLibraryState,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    onImport: () -> Unit,
    onOpen: (Document) -> Unit,
    onDelete: (Document) -> Unit,
    onCategories: (Document) -> Unit,
    onToggleSelection: (String) -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state.isLoading || state.isImporting -> com.folio.reader.ui.components.LoadingPlaceholder(Modifier.fillMaxSize())
            state.errorMessage != null -> com.folio.reader.ui.components.EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                headline = "Couldn't load documents",
                body = state.errorMessage
            )
            state.items.isEmpty() -> com.folio.reader.ui.components.EmptyState(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                headline = if (state.query.isBlank()) "No documents in category" else "No matching documents",
                body = if (state.query.isBlank()) "Import a document or choose another category" else "Try a different title, filename, author, description, or format",
                action = if (state.query.isBlank()) ({
                    Button(onClick = onImport) { Text("Import a document") }
                }) else null
            )
            else -> when (state.viewMode) {
                DocumentViewMode.GRID -> DocumentGrid(
                    items = state.items,
                    selectedIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    onCategories = onCategories,
                    onToggleSelection = onToggleSelection
                )
                DocumentViewMode.LIST -> DocumentList(
                    items = state.items,
                    selectedIds = selectedIds,
                    isSelectionMode = isSelectionMode,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    onCategories = onCategories,
                    onToggleSelection = onToggleSelection
                )
            }
        }
    }
}

@Composable
private fun LibraryContent(
    books: List<Book>?,
    libraryEmpty: Boolean,
    viewMode: LibraryViewModel.ViewMode,
    selectedBooks: Set<String>,
    isSelectionMode: Boolean,
    preserveFeaturedDuringSelection: Boolean,
    finishEstimates: Map<String, String>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onImportClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // The filter chips ride the masthead's rail (LibraryScreen's `railContent`)
        // where they fold away with it as the shelf scrolls, so the shelf itself
        // starts straight at the content.
        // Skeleton→content is a motion-gated Crossfade keyed on the load state
        // (books == null) alone, so the aligned skeleton dissolves into the shelf
        // once the read lands — and only then, since keying on the list itself
        // would re-cross the whole grid on every sort or filter.
        val motion = rememberMotionEnabled()
        Crossfade(
            targetState = books == null,
            animationSpec = if (motion) tween(FolioTokens.motionStandard.toInt()) else snap(),
            label = "library skeleton",
        ) { loading ->
            if (loading) {
                // The shelf is measured, not spun for. See LibrarySkeleton: a lone
                // spinner on an empty page is what the tab cross-fade carries in, and
                // the page then appears all at once when the read lands.
                LibrarySkeleton(modifier = Modifier.fillMaxSize())
            } else if (books == null || books.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    com.folio.reader.ui.components.EmptyState(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        headline = if (libraryEmpty) "No books in library" else "No books in this collection",
                        body = if (libraryEmpty) {
                            "Import your first EPUB to get started"
                        } else {
                            "Move books here or choose another collection"
                        },
                        action = if (libraryEmpty) {
                            {
                                Button(onClick = onImportClick) {
                                    Text("Import EPUB")
                                }
                            }
                        } else null
                    )
                }
            } else {
                // Grid↔List↔Compact dissolves as one surface reconfiguring rather than a
                // hard cut between two different lazy layouts. The cover morph is
                // suspended for the length of the cross: while it runs, the outgoing
                // grid and the incoming list are composed together and each holds this
                // book's cover key, and two live copies of one key in one scope is the
                // case the shared-transition registry cannot resolve.
                //
                // A SizeTransform is safe here in a way it is not for the shelf swap: both
                // sides render the identical book list, so the row count cannot change
                // and the incoming layout never re-columns. Only a short shelf qualifies —
                // see folioSizeTransformEligible.
                val swapMotion = rememberMotionEnabled()
                val swapInFlight = rememberSwapInFlight(viewMode)
                AnimatedContent(
                    targetState = viewMode,
                    transitionSpec = {
                        folioFadeSwap(
                            swapMotion,
                            sizeTransform = folioSwapSizeTransform()
                                .takeIf { folioSizeTransformEligible(books.size) },
                        )
                    },
                    label = "view mode swap",
                ) { mode ->
                    FolioSharedElementsSuppressed(swapInFlight) {
                        when (mode) {
                            LibraryViewModel.ViewMode.GRID -> BookGrid(
                                books = books,
                                onBookClick = onBookClick,
                                onBookLongClick = onBookLongClick,
                                onDeleteBook = onDeleteBook,
                                selectedBooks = selectedBooks,
                                isSelectionMode = isSelectionMode,
                                finishEstimates = finishEstimates,
                                preserveFeaturedDuringSelection = preserveFeaturedDuringSelection,
                            )

                            LibraryViewModel.ViewMode.LIST -> BookList(
                                books,
                                onBookClick,
                                onBookLongClick,
                                onDeleteBook,
                                selectedBooks,
                                isSelectionMode,
                                finishEstimates
                            )

                            LibraryViewModel.ViewMode.COMPACT -> BookCompactList(
                                books,
                                onBookClick,
                                onBookLongClick,
                                onDeleteBook,
                                selectedBooks,
                                isSelectionMode,
                                finishEstimates
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Books/Manga switch.
 *
 * Public because the manga shelf carries it on *its* rail: in manga mode the shelf
 * already needs a row for categories, downloads and the collection editor, so the
 * switch joins that row instead of adding a second one above it.
 *
 * A segmented track rather than two chips: the choice is exclusive, and one track
 * costs a third of the width two chips did — which is what let the rail hold the
 * switch and the filters at once.
 */
@Composable
fun LibraryModeSwitch(
    mode: LibraryMode,
    onModeChange: (LibraryMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    com.folio.reader.ui.components.FolioSegmented(
        options = listOf("Books", "Manga", "Documents"),
        selectedIndex = libraryModeDisplayIndex(mode),
        onSelect = { index -> onModeChange(libraryModeAtDisplayIndex(index)) },
        modifier = modifier,
    )
}

/**
 * View mode as one row of three marks at the head of the Display menu, instead of
 * three menu items with tick icons. The choice is visual, so the control is too —
 * and it saves two thirds of the vertical space those items used.
 */
@Composable
private fun ViewModeRow(selected: Int, optionCount: Int = 3, onSelect: (Int) -> Unit) {
    val shape = com.folio.reader.ui.theme.FolioShapes.pill
    val icons = listOf(
        Icons.Filled.GridView,
        Icons.AutoMirrored.Filled.ViewList,
        Icons.Filled.ViewAgenda,
    )
    val labels = listOf("Grid view", "List view", "Compact view")
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.take(optionCount).forEachIndexed { index, icon ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(shape)
                    .background(
                        if (active) FolioTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent,
                        shape,
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = labels[index],
                    tint = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewCheck(active: Boolean) {
    if (active) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Filled.Check,
            contentDescription = null,
            tint = FolioTheme.colors.primary,
            modifier = Modifier.size(18.dp),
        )
    } else {
        Spacer(modifier = Modifier.size(18.dp))
    }
}
