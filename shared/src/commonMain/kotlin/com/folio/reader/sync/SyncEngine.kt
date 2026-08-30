package com.folio.reader.sync

import com.folio.reader.database.*
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.firebase.FsBook
import com.folio.reader.firebase.FsBookmark
import com.folio.reader.firebase.FsCollection
import com.folio.reader.firebase.FsHighlight
import com.folio.reader.firebase.FsManga
import com.folio.reader.firebase.FsMangaCategory
import com.folio.reader.firebase.FsMangaChapter
import com.folio.reader.firebase.FsMangaNote
import com.folio.reader.firebase.FsNote
import com.folio.reader.firebase.FsQuote
import com.folio.reader.firebase.FsReadingPosition
import com.folio.reader.firebase.FsReadingSession
import com.folio.reader.firebase.FsRevisitItem
import com.folio.reader.firebase.FsSeries
import com.folio.reader.firebase.FsSettings
import com.folio.reader.firebase.FsTag
import com.folio.reader.model.*
import com.folio.reader.settings.ReaderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val EDIT_SYNC_DEBOUNCE_MS = 3_000L

/** Hard cap for the final flush at app exit — never felt by the user. */
private const val EXIT_SYNC_BUDGET_MS = 5_000L

/** Clock-skew tolerance when fetching sessions incrementally. */
private const val SESSION_FETCH_SKEW_MARGIN_MS = 24L * 60L * 60L * 1000L

/** Raw settings key recording the updatedAt of the last applied remote settings doc. */
private const val KEY_SETTINGS_APPLIED_AT = "remote_settings_applied_at"

class SyncEngine(
    private val scope: CoroutineScope,
    private val syncRepository: SyncRepository,
    private val bookRepository: BookRepository,
    private val positionRepository: ReadingPositionRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceRepository: DeviceRepository,
    private val collectionRepository: CollectionRepository,
    private val seriesRepository: SeriesRepository,
    private val tagRepository: TagRepository,
    private val quoteRepository: QuoteRepository,
    private val revisitRepository: RevisitRepository,
    private val firestoreSync: FirestoreSync,
    val storageSync: StorageSync,
    private val config: SyncConfig,
    private val deviceId: String,
    private val mangaRepository: com.folio.reader.manga.MangaRepository? = null,
    private val mangaChapterRepository: com.folio.reader.manga.MangaChapterRepository? = null,
    private val mangaNoteRepository: com.folio.reader.manga.MangaNoteRepository? = null,
    private val mangaCategoryRepository: com.folio.reader.manga.MangaCategoryRepository? = null,
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState())
    val syncState: Flow<SyncState> = _syncState.asStateFlow()

    private var isRunning = false
    private var loopJob: kotlinx.coroutines.Job? = null
    private val syncMutex = Mutex()

    fun start() {
        if (isRunning) return
        isRunning = true
        loopJob = scope.launch { runSyncLoop() }
    }

    fun stop() {
        isRunning = false
        loopJob?.cancel()
        loopJob = null
        debounceJob?.cancel()
    }

    private var debounceJob: kotlinx.coroutines.Job? = null

    /**
     * Local edits always enter the outbox. An explicit [immediate] sync runs at
     * once; otherwise edits schedule one debounced sync, so a burst of frequent
     * writes (scroll positions) collapses into a single request while a discrete
     * change like a deletion still leaves this device seconds later instead of
     * waiting for the next periodic loop.
     */
    fun triggerSync(immediate: Boolean = false) {
        debounceJob?.cancel()
        if (immediate) {
            scope.launch { syncOnce() }
        } else {
            debounceJob = scope.launch {
                delay(EDIT_SYNC_DEBOUNCE_MS)
                syncOnce()
            }
        }
    }

    /** Runs one full sync cycle synchronously; used by tests and manual refresh. */
    internal suspend fun syncOnce() {
        syncMutex.withLock {
            performSync()
        }
    }

    private suspend fun runSyncLoop() {
        while (isRunning) {
            syncMutex.withLock {
                performSync()
            }
            delay(config.syncIntervalMinutes * 60 * 1000L)
        }
    }

    private suspend fun performSync() {
        // Recover work interrupted by a previous process shutdown before reading the queue.
        syncRepository.recoverStaleSyncing(
            Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - 60_000L)
        )
        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        // App graphs create this engine only after resolving credentials, which may
        // include the bundled project ID. Do not require that fallback to also be
        // persisted in ReaderSettings or sync will silently never start.
        val shouldSync = config.autoSync && settings?.cloudSyncEnabled != false
        if (!shouldSync) {
            _syncState.update { it.copy(isSyncing = false, isConfigured = false) }
            return
        }

        _syncState.update { it.copy(isSyncing = true, lastError = null, isConfigured = true) }

        try {
            // 1. Authenticate
            firestoreSync.authenticate()

            // 2. Fetch the cloud catalog before uploading. This lets a device
            // bootstrap a pre-existing local library without re-uploading books
            // that already exist on another device under a different local id.
            fetchRemoteChanges()
            enqueueBooksMissingRemotely()
            backfillAnnotationsOnce()

            // 3. Push local changes
            pushLocalChanges()

            // 4. Apply remote changes locally (last-writer-wins per entity)
            applyRemoteChanges()

            // 5. Update sync state from the complete eligible outbox, not a work batch.
            val remainingPending = runCatching { syncRepository.getPendingSyncCount() }.getOrDefault(0)
            _syncState.update {
                it.copy(
                    lastIncrementalSyncAt = Clock.System.now(),
                    isSyncing = false,
                    pendingUploadCount = remainingPending,
                    pendingDownloadCount = 0
                )
            }

        } catch (e: Exception) {
            println("❌ Sync engine error: ${e.message}")
            e.printStackTrace()
            val remainingPending = runCatching { syncRepository.getPendingSyncCount() }.getOrDefault(0)
            _syncState.update {
                it.copy(
                    isSyncing = false,
                    lastError = e.message ?: e.toString(),
                    pendingUploadCount = remainingPending
                )
            }
        }
    }

    private suspend fun pushLocalChanges() {
        val pending = syncRepository.getPendingSync(100)
        _syncState.update { it.copy(pendingUploadCount = syncRepository.getPendingSyncCount()) }

        for (item in pending) {
            // Early releases represented hard deletes with an empty payload. The
            // current transport cannot reconstruct such an entity, so drop these
            // stale entries. Book deletes are the exception: they push a tombstone
            // so the deletion reaches other devices. Current soft-deleted
            // annotations include their entity payload.
            if (item.operation == SyncOperation.DELETE && item.payload == "{}" && item.entityType != "book") {
                syncRepository.markSynced(item.id)
                continue
            }

            syncRepository.markSyncing(item.id)
            try {
                when (item.entityType) {
                    "book" -> pushBook(item)
                    "position" -> pushPosition(item)
                    "highlight" -> pushHighlight(item)
                    "note" -> pushNote(item)
                    "bookmark" -> pushBookmark(item)
                    "session" -> pushSession(item)
                    "settings" -> pushSettings(item)
                    "tag" -> pushTag(item)
                    "collection" -> pushCollection(item)
                    "series" -> pushSeries(item)
                    "quote" -> pushQuote(item)
                    "revisit" -> pushRevisit(item)
                    "manga" -> pushManga(item)
                    "manga_chapter" -> pushMangaChapter(item)
                    "manga_note" -> pushMangaNote(item)
                    "manga_category" -> pushMangaCategory(item)
                }
                syncRepository.markSynced(item.id)
            } catch (e: Exception) {
                syncRepository.markError(item.id)
                println("❌ Failed to upload ${item.entityType}/${item.entityId}: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    private suspend fun pushBook(item: SyncQueueItem) {
        if (item.operation == SyncOperation.DELETE) {
            // Tombstone: metadata-only marker telling other devices this book was
            // removed. The local row is already gone, so only the id travels.
            firestoreSync.upsertBook(
                FsBook(
                    id = item.entityId,
                    title = "",
                    epubHash = "",
                    epubFileSize = 0,
                    addedAt = 0,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                    deviceId = deviceId,
                    isDeleted = true
                )
            )
            return
        }
        // Always sync book metadata; EPUB file body upload is handled separately via uploadBookToCloud (opt-in via syncEpubs setting)
        val book = Json.Default.decodeFromString(Book.serializer(), item.payload)
        // A fresher remote copy wins; uploading this stale payload would clobber it.
        val remote = fetchedBooks.firstOrNull { it.id == book.id }
        if (remote != null && remote.updatedAt > book.updatedAt.toEpochMilliseconds()) return
        val fsBook = FsBook.fromBook(book, deviceId)
        firestoreSync.upsertBook(fsBook)
    }

    private suspend fun pushPosition(item: SyncQueueItem) {
        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        if (settings?.syncPositions == false) return

        val position = Json.Default.decodeFromString(ReadingPosition.serializer(), item.payload)
        val fsPosition = FsReadingPosition(
            bookId = position.bookId,
            deviceId = position.deviceId,
            chapterId = position.chapterId,
            spineIndex = position.spineIndex,
            contentLocator = position.contentLocator,
            characterOffset = position.characterOffset,
            paragraphIndex = position.paragraphIndex,
            normalizedProgress = position.normalizedProgress,
            chapterProgress = position.chapterProgress,
            scrollOffset = position.scrollOffset,
            updatedAt = position.updatedAt.toEpochMilliseconds(),
            previousPosition = position.previousPosition?.let {
                Json.Default.encodeToString(
                    ReadingPosition.serializer(),
                    it
                )
            }
        )
        firestoreSync.upsertPosition(fsPosition)
    }

    private suspend fun pushHighlight(item: SyncQueueItem) {
        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        if (settings?.syncAnnotations == false) return

        val highlight = Json.Default.decodeFromString(Highlight.serializer(), item.payload)
        // A fresher remote copy wins; uploading this stale payload would resurrect
        // a tombstone or overwrite an edit made on another device. applyRemoteChanges
        // applies that fresher remote state locally afterwards.
        val remote = fetchedHighlights.firstOrNull { it.id == highlight.id }
        if (remote != null && remote.updatedAt > highlight.updatedAt.toEpochMilliseconds()) return
        val fsHighlight = FsHighlight.fromHighlight(highlight)
        firestoreSync.upsertHighlight(fsHighlight)
    }

    private suspend fun pushNote(item: SyncQueueItem) {
        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        if (settings?.syncAnnotations == false) return

        val note = Json.Default.decodeFromString(Note.serializer(), item.payload)
        val remote = fetchedNotes.firstOrNull { it.id == note.id }
        if (remote != null && remote.updatedAt > note.updatedAt.toEpochMilliseconds()) return
        val fsNote = FsNote.fromNote(note)
        firestoreSync.upsertNote(fsNote)
    }

    private suspend fun pushBookmark(item: SyncQueueItem) {
        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        if (settings?.syncAnnotations == false) return

        val bookmark = Json.Default.decodeFromString(Bookmark.serializer(), item.payload)
        val remote = fetchedBookmarks.firstOrNull { it.id == bookmark.id }
        if (remote != null && remote.updatedAt > bookmark.updatedAt.toEpochMilliseconds()) return
        val fsBookmark = FsBookmark.fromBookmark(bookmark)
        firestoreSync.upsertBookmark(fsBookmark)
    }

    private suspend fun pushSession(item: SyncQueueItem) {
        val session = Json.Default.decodeFromString(ReadingSession.serializer(), item.payload)
        val fsSession = FsReadingSession(
            id = session.id,
            bookId = session.bookId,
            cycleId = session.cycleId,
            deviceId = session.deviceId,
            startedAt = session.startedAt.toEpochMilliseconds(),
            endedAt = session.endedAt?.toEpochMilliseconds(),
            durationMs = session.durationMs,
            startPosition = Json.Default.encodeToString(ReadingPosition.serializer(), session.startPosition),
            endPosition = session.endPosition?.let { Json.Default.encodeToString(ReadingPosition.serializer(), it) },
            startProgress = session.startProgress,
            endProgress = session.endProgress,
            wordsRead = session.wordsRead,
            isActive = session.isActive
        )
        firestoreSync.upsertSession(fsSession)
    }

    private suspend fun pushSettings(item: SyncQueueItem) {
        val current = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        if (current?.syncSettings == false) return

        val settings = Json.Default.decodeFromString(ReaderSettings.serializer(), item.payload)
        // Credentials never leave the device: the payload would otherwise carry the
        // account password and API key into the cloud document (and every restore).
        val shareable = settings.copy(
            firebaseApiKey = "",
            syncAccountEmail = "",
            syncAccountPassword = ""
        )
        val fsSettings = FsSettings(
            userId = "", // From auth
            global = Json.Default.encodeToString(shareable),
            bookSettings = emptyMap(),
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            deviceId = deviceId
        )
        firestoreSync.upsertSettings(fsSettings)
    }

    private suspend fun pushTag(item: SyncQueueItem) {
        val tag = Json.Default.decodeFromString(Tag.serializer(), item.payload)
        firestoreSync.upsertTag(
            FsTag(
                id = tag.id,
                name = tag.name,
                color = tag.color,
                createdAt = tag.createdAt.toEpochMilliseconds(),
                deviceId = deviceId,
                updatedAt = tag.updatedAt.toEpochMilliseconds()
            )
        )
    }

    private suspend fun pushCollection(item: SyncQueueItem) {
        val collection = Json.Default.decodeFromString(FolioCollection.serializer(), item.payload)
        firestoreSync.upsertCollection(
            FsCollection(
                id = collection.id,
                name = collection.name,
                color = collection.color,
                sortOrder = collection.sortOrder,
                createdAt = collection.createdAt.toEpochMilliseconds(),
                deviceId = deviceId,
                updatedAt = collection.updatedAt.toEpochMilliseconds()
            )
        )
    }

    private suspend fun pushSeries(item: SyncQueueItem) {
        val series = Json.Default.decodeFromString(Series.serializer(), item.payload)
        firestoreSync.upsertSeries(
            FsSeries(
                id = series.id,
                name = series.name,
                sortOrder = series.sortOrder,
                deviceId = deviceId,
                updatedAt = series.updatedAt.toEpochMilliseconds()
            )
        )
    }

    private suspend fun pushQuote(item: SyncQueueItem) {
        val quote = Json.Default.decodeFromString(Quote.serializer(), item.payload)
        firestoreSync.upsertQuote(
            FsQuote(
                id = quote.id,
                bookId = quote.bookId,
                chapterId = quote.chapterId,
                highlightId = quote.highlightId,
                text = quote.text,
                note = quote.note,
                createdAt = quote.createdAt.toEpochMilliseconds(),
                deviceId = quote.deviceId.ifBlank { deviceId }
            )
        )
    }

    private suspend fun pushRevisit(item: SyncQueueItem) {
        val revisit = Json.Default.decodeFromString(RevisitItem.serializer(), item.payload)
        firestoreSync.upsertRevisitItem(
            FsRevisitItem(
                id = revisit.id,
                bookId = revisit.bookId,
                chapterId = revisit.chapterId,
                type = revisit.type.value,
                sourceId = revisit.sourceId,
                note = revisit.note,
                createdAt = revisit.createdAt.toEpochMilliseconds(),
                resolvedAt = revisit.resolvedAt?.toEpochMilliseconds(),
                deviceId = revisit.deviceId.ifBlank { deviceId }
            )
        )
    }

    private suspend fun pushManga(item: SyncQueueItem) {
        val fs = Json.Default.decodeFromString(FsManga.serializer(), item.payload)
        val remote = fetchedManga.firstOrNull { it.id == fs.id }
        if (remote != null && remote.updatedAt > fs.updatedAt && !fs.isDeleted) return
        firestoreSync.upsertManga(fs.copy(deviceId = fs.deviceId.ifBlank { deviceId }))
    }

    private suspend fun pushMangaChapter(item: SyncQueueItem) {
        val fs = Json.Default.decodeFromString(FsMangaChapter.serializer(), item.payload)
        val remote = fetchedMangaChapters.firstOrNull { it.id == fs.id }
        if (remote != null && remote.updatedAt > fs.updatedAt) return
        firestoreSync.upsertMangaChapter(fs.copy(deviceId = fs.deviceId.ifBlank { deviceId }))
    }

    private suspend fun pushMangaNote(item: SyncQueueItem) {
        val fs = Json.Default.decodeFromString(FsMangaNote.serializer(), item.payload)
        val remote = fetchedMangaNotes.firstOrNull { it.id == fs.id }
        if (remote != null && remote.updatedAt > fs.updatedAt && !fs.isDeleted) return
        firestoreSync.upsertMangaNote(fs.copy(deviceId = fs.deviceId.ifBlank { deviceId }))
    }

    private suspend fun pushMangaCategory(item: SyncQueueItem) {
        val fs = Json.Default.decodeFromString(FsMangaCategory.serializer(), item.payload)
        val remote = fetchedMangaCategories.firstOrNull { it.id == fs.id }
        if (remote != null && remote.updatedAt > fs.updatedAt && !fs.isDeleted) return
        firestoreSync.upsertMangaCategory(fs.copy(deviceId = fs.deviceId.ifBlank { deviceId }))
    }

    private var fetchedBooks: List<FsBook> = emptyList()
    private var fetchedPositions: List<FsReadingPosition> = emptyList()
    private var fetchedHighlights: List<FsHighlight> = emptyList()
    private var fetchedNotes: List<FsNote> = emptyList()
    private var fetchedBookmarks: List<FsBookmark> = emptyList()
    private var fetchedSessions: List<FsReadingSession> = emptyList()
    private var fetchedCollections: List<FsCollection> = emptyList()
    private var fetchedSeries: List<FsSeries> = emptyList()
    private var fetchedTags: List<FsTag> = emptyList()
    private var fetchedQuotes: List<FsQuote> = emptyList()
    private var fetchedRevisitItems: List<FsRevisitItem> = emptyList()
    private var fetchedManga: List<FsManga> = emptyList()
    private var fetchedMangaChapters: List<FsMangaChapter> = emptyList()
    private var fetchedMangaNotes: List<FsMangaNote> = emptyList()
    private var fetchedMangaCategories: List<FsMangaCategory> = emptyList()
    private var fetchedSettings: FsSettings? = null

    private suspend fun fetchRemoteChanges() {
        fetchedBooks = firestoreSync.fetchBooks()
        fetchedPositions = firestoreSync.fetchPositions()
        fetchedHighlights = firestoreSync.fetchHighlights()
        fetchedNotes = firestoreSync.fetchNotes()
        fetchedBookmarks = firestoreSync.fetchBookmarks()
        // Sessions only ever gain entries, so pull just what is newer than the
        // latest session already applied from other devices. The margin absorbs
        // clock skew between devices; duplicates are id-checked on apply. No known
        // remote sessions yet means cursor 0 — the original full fetch.
        val cursor = runCatching { sessionRepository.maxStartedAtExcludingDevice(deviceId) }
            .getOrNull()
            ?.toEpochMilliseconds()
            ?.minus(SESSION_FETCH_SKEW_MARGIN_MS)
            ?.coerceAtLeast(0L)
            ?: 0L
        fetchedSessions = firestoreSync.fetchSessions(cursor)
        fetchedCollections = firestoreSync.fetchCollections()
        fetchedSeries = firestoreSync.fetchSeries()
        fetchedTags = firestoreSync.fetchTags()
        fetchedQuotes = firestoreSync.fetchQuotes()
        fetchedRevisitItems = firestoreSync.fetchRevisitItems()
        fetchedManga = runCatching { firestoreSync.fetchManga() }.getOrDefault(emptyList())
        fetchedMangaChapters = runCatching { firestoreSync.fetchMangaChapters() }.getOrDefault(emptyList())
        fetchedMangaNotes = runCatching { firestoreSync.fetchMangaNotes() }.getOrDefault(emptyList())
        fetchedMangaCategories = runCatching { firestoreSync.fetchMangaCategories() }.getOrDefault(emptyList())
        fetchedSettings = runCatching { firestoreSync.fetchSettings() }.getOrNull()
        // Deliberately no pendingDownloadCount publication: remote documents are
        // applied within this same cycle, never queued, so advertising the fetched
        // total would only make the UI's pending count spike and reset each cycle.
    }

    private suspend fun enqueueBooksMissingRemotely() {
        val remoteBookIds = fetchedBooks.map { it.id }.toSet()
        val remoteHashes = fetchedBooks.mapNotNull { it.epubHash.takeIf(String::isNotBlank) }.toSet()
        val remoteIsbns = fetchedBooks.mapNotNull { it.isbn?.takeIf(String::isNotBlank) }.toSet()

        for (book in bookRepository.getAllBooks().first()) {
            val existsRemotely = book.id in remoteBookIds ||
                    book.epubHash in remoteHashes ||
                    (!book.isbn.isNullOrBlank() && book.isbn in remoteIsbns)
            if (!existsRemotely) {
                syncRepository.enqueueSync(
                    "book",
                    book.id,
                    SyncOperation.CREATE,
                    Json.Default.encodeToString(Book.serializer(), book)
                )
            }
        }
    }

    /**
     * One-time pass: annotations created before sync existed (or before this
     * device ever had credentials) never entered the outbox. Queue every local
     * highlight, note and bookmark once; the flag in the settings key/value
     * store prevents repeats. Queue ids are entity-keyed, so this is idempotent
     * even if it races with live edits.
     */
    private suspend fun backfillAnnotationsOnce() {
        val done = runCatching { settingsRepository.getRaw("annotations_backfilled_at") }.getOrNull()
        if (!done.isNullOrBlank()) return
        var enqueued = 0
        // A failure must not stamp the done-flag, or the backfill would never
        // be retried and pre-sync annotations would stay device-local forever.
        val result = runCatching {
            for (book in bookRepository.getAllBooks().first()) {
                highlightRepository.getHighlightsForBook(book.id).first().forEach { h ->
                    syncRepository.enqueueSync(
                        "highlight", h.id, SyncOperation.UPSERT,
                        Json.Default.encodeToString(Highlight.serializer(), h)
                    ); enqueued++
                }
                noteRepository.getNotesForBook(book.id).first().forEach { n ->
                    syncRepository.enqueueSync(
                        "note", n.id, SyncOperation.UPSERT,
                        Json.Default.encodeToString(Note.serializer(), n)
                    ); enqueued++
                }
                bookmarkRepository.getBookmarksForBook(book.id).first().forEach { b ->
                    syncRepository.enqueueSync(
                        "bookmark", b.id, SyncOperation.UPSERT,
                        Json.Default.encodeToString(Bookmark.serializer(), b)
                    ); enqueued++
                }
            }
        }
        if (result.isSuccess) {
            runCatching {
                settingsRepository.setRaw("annotations_backfilled_at", Clock.System.now().toString())
            }
        }
        if (enqueued > 0) println("☁️ Annotation backfill queued $enqueued item(s) for cloud sync")
    }

    private suspend fun applyRemoteChanges() {
        applyRemoteBooks()
        applyRemotePositions()
        applyRemoteAnnotations()
        applyRemoteSessions()
        applyRemoteSettings()
        applySecondaryEntities()
        applyMangaEntities()

        fetchedBooks = emptyList()
        fetchedPositions = emptyList()
        fetchedHighlights = emptyList()
        fetchedNotes = emptyList()
        fetchedBookmarks = emptyList()
        fetchedSessions = emptyList()
        fetchedCollections = emptyList()
        fetchedSeries = emptyList()
        fetchedTags = emptyList()
        fetchedQuotes = emptyList()
        fetchedRevisitItems = emptyList()
        fetchedManga = emptyList()
        fetchedMangaChapters = emptyList()
        fetchedMangaNotes = emptyList()
        fetchedMangaCategories = emptyList()
        fetchedSettings = null
    }

    /**
     * Settings sync is LWW by the document's updatedAt and strictly one-way per
     * cycle: the applied watermark lives in the raw settings store, so an applied
     * document is never re-applied, and the local save suppresses its sync event,
     * so it is never echoed back to the cloud. Local credentials are always kept —
     * the cloud document carries none (see pushSettings).
     */
    private suspend fun applyRemoteSettings() {
        val remote = fetchedSettings ?: return
        if (remote.deviceId == deviceId) return
        val current = runCatching { settingsRepository.getGlobalSettings() }.getOrNull() ?: return
        if (!current.syncSettings) return
        val appliedAt = runCatching { settingsRepository.getRaw(KEY_SETTINGS_APPLIED_AT) }
            .getOrNull()?.toLongOrNull() ?: 0L
        if (remote.updatedAt <= appliedAt) return
        val incoming = runCatching {
            Json.Default.decodeFromString(ReaderSettings.serializer(), remote.global)
        }.getOrNull() ?: return
        val merged = incoming.copy(
            firebaseApiKey = current.firebaseApiKey,
            firebaseProjectId = current.firebaseProjectId,
            syncAccountEmail = current.syncAccountEmail,
            syncAccountPassword = current.syncAccountPassword
        )
        runCatching { settingsRepository.saveGlobalSettings(merged, emitSyncEvent = false) }
        runCatching { settingsRepository.setRaw(KEY_SETTINGS_APPLIED_AT, remote.updatedAt.toString()) }
    }

    /**
     * Secondary entity types merge by id. Collections, series and tags carry an
     * updatedAt watermark, so an edit (rename, recolor) made on another device
     * overwrites the older local copy — last-write-wins, same as annotations.
     * Quotes and revisit items remain insert-if-missing; revisit items adopt a
     * remote resolvedAt when the local copy is still unresolved.
     */
    private suspend fun applySecondaryEntities() {
        val localCollections = collectionRepository.getAllCollections().first().associateBy { it.id }
        for (remote in fetchedCollections) {
            if (remote.deviceId == deviceId) continue
            val local = localCollections[remote.id]
            val remoteMs = remote.updatedAt.takeIf { it > 0 } ?: remote.createdAt
            if (local == null) {
                runCatching {
                    collectionRepository.insertCollection(
                        FolioCollection(
                            id = remote.id,
                            name = remote.name,
                            color = remote.color,
                            sortOrder = remote.sortOrder,
                            createdAt = Instant.fromEpochMilliseconds(remote.createdAt),
                            updatedAt = Instant.fromEpochMilliseconds(remoteMs)
                        ),
                        emitSyncEvent = false
                    )
                }
            } else if (local.updatedAt.toEpochMilliseconds() < remoteMs) {
                runCatching {
                    collectionRepository.updateCollection(
                        local.copy(
                            name = remote.name,
                            color = remote.color,
                            sortOrder = remote.sortOrder,
                            updatedAt = Instant.fromEpochMilliseconds(remoteMs)
                        ),
                        emitSyncEvent = false
                    )
                }
            }
        }

        for (remote in fetchedSeries) {
            if (remote.deviceId == deviceId) continue
            val local = runCatching { seriesRepository.getSeries(remote.id) }.getOrNull()
            if (local == null) {
                runCatching {
                    seriesRepository.insertSeries(
                        Series(
                            id = remote.id,
                            name = remote.name,
                            sortOrder = remote.sortOrder,
                            updatedAt = Instant.fromEpochMilliseconds(remote.updatedAt.takeIf { it > 0 } ?: Clock.System.now().toEpochMilliseconds())
                        ),
                        emitSyncEvent = false
                    )
                }
            } else if (remote.updatedAt > 0 && local.updatedAt.toEpochMilliseconds() < remote.updatedAt) {
                runCatching {
                    seriesRepository.updateSeries(
                        local.copy(name = remote.name, sortOrder = remote.sortOrder, updatedAt = Instant.fromEpochMilliseconds(remote.updatedAt)),
                        emitSyncEvent = false
                    )
                }
            }
        }

        val localTags = tagRepository.getAllTags().first().associateBy { it.id }
        for (remote in fetchedTags) {
            if (remote.deviceId == deviceId) continue
            val local = localTags[remote.id]
            val remoteMs = remote.updatedAt.takeIf { it > 0 } ?: remote.createdAt
            if (local == null) {
                runCatching {
                    tagRepository.insertTag(
                        Tag(
                            id = remote.id,
                            name = remote.name,
                            color = remote.color,
                            createdAt = Instant.fromEpochMilliseconds(remote.createdAt),
                            updatedAt = Instant.fromEpochMilliseconds(remoteMs)
                        ),
                        emitSyncEvent = false
                    )
                }
            } else if (local.updatedAt.toEpochMilliseconds() < remoteMs) {
                runCatching {
                    tagRepository.updateTag(
                        local.copy(name = remote.name, color = remote.color, updatedAt = Instant.fromEpochMilliseconds(remoteMs)),
                        emitSyncEvent = false
                    )
                }
            }
        }

        val localQuoteIds = quoteRepository.getAllQuotes().first().map { it.id }.toSet()
        for (remote in fetchedQuotes) {
            if (remote.deviceId == deviceId || remote.id in localQuoteIds) continue
            runCatching {
                quoteRepository.insertQuote(
                    Quote(
                        id = remote.id,
                        bookId = remote.bookId,
                        chapterId = remote.chapterId,
                        highlightId = remote.highlightId,
                        text = remote.text,
                        note = remote.note,
                        createdAt = Instant.fromEpochMilliseconds(remote.createdAt),
                        deviceId = remote.deviceId
                    ),
                    emitSyncEvent = false
                )
            }
        }

        val localRevisitIds = revisitRepository.getUnresolvedRevisitItems().first().map { it.id }.toSet()
        for (remote in fetchedRevisitItems) {
            if (remote.deviceId == deviceId) continue
            if (remote.resolvedAt == null && remote.id !in localRevisitIds) {
                runCatching {
                    revisitRepository.insertRevisitItem(
                        RevisitItem(
                            id = remote.id,
                            bookId = remote.bookId,
                            chapterId = remote.chapterId,
                            type = RevisitType.fromValue(remote.type),
                            sourceId = remote.sourceId,
                            note = remote.note,
                            createdAt = Instant.fromEpochMilliseconds(remote.createdAt),
                            resolvedAt = null,
                            deviceId = remote.deviceId
                        ),
                        emitSyncEvent = false
                    )
                }
            } else if (remote.resolvedAt != null && remote.id in localRevisitIds) {
                runCatching { revisitRepository.resolveRevisitItem(remote.id, emitSyncEvent = false) }
            }
        }
    }

    /**
     * Manga entities merge by id with last-write-wins on updatedAt, mirroring the
     * secondary entity merge. Chapter states only adopt read/bookmark/progress
     * fields; the chapter catalog itself is source-owned and never synced.
     */
    private suspend fun applyMangaEntities() {
        val mangaRepo = mangaRepository ?: return
        val chapterRepo = mangaChapterRepository
        val noteRepo = mangaNoteRepository

        for (remote in fetchedManga) {
            if (remote.deviceId == deviceId) continue
            val local = mangaRepo.get(remote.id)
            if (remote.isDeleted) {
                if (local != null) runCatching { mangaRepo.delete(remote.id, emitSyncEvent = false) }
                continue
            }
            val remoteMs = remote.updatedAt
            if (local == null || local.updatedAt.toEpochMilliseconds() < remoteMs) {
                runCatching {
                    mangaRepo.upsert(
                        com.folio.reader.manga.MangaEntry(
                            id = remote.id,
                            sourceId = remote.sourceId,
                            sourceName = remote.sourceName,
                            url = remote.url,
                            title = remote.title,
                            author = remote.author,
                            artist = remote.artist,
                            description = remote.description,
                            genres = runCatching {
                                (Json.parseToJsonElement(remote.genres) as kotlinx.serialization.json.JsonArray)
                                    .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            }.getOrDefault(emptyList()),
                            status = com.folio.reader.manga.MangaStatus.fromValue(remote.status),
                            thumbnailUrl = remote.thumbnailUrl,
                            inLibrary = remote.favorite,
                            initialized = remote.initialized,
                            addedAt = Instant.fromEpochMilliseconds(remote.addedAt),
                            updatedAt = Instant.fromEpochMilliseconds(remoteMs),
                        ),
                        emitSyncEvent = false
                    )
                }
            }
        }

        if (chapterRepo != null) {
            for (remote in fetchedMangaChapters) {
                if (remote.deviceId == deviceId) continue
                val local = chapterRepo.getChapter(remote.id) ?: continue
                if (local.updatedAt.toEpochMilliseconds() < remote.updatedAt) {
                    runCatching {
                        chapterRepo.applyRemoteState(
                            remote.id,
                            read = remote.read,
                            bookmarked = remote.bookmarked,
                            lastPageRead = remote.lastPageRead,
                            updatedAt = Instant.fromEpochMilliseconds(remote.updatedAt),
                            emitSyncEvent = false,
                            totalPages = remote.totalPages,
                        )
                    }
                }
            }
        }

        if (noteRepo != null) {
            for (remote in fetchedMangaNotes) {
                if (remote.deviceId == deviceId) continue
                val local = noteRepo.get(remote.id)
                if (remote.isDeleted) {
                    if (local != null) runCatching { noteRepo.delete(remote.id, emitSyncEvent = false) }
                    continue
                }
                if (local == null || local.updatedAt.toEpochMilliseconds() < remote.updatedAt) {
                    runCatching {
                        noteRepo.upsert(
                            com.folio.reader.manga.MangaNote(
                                id = remote.id,
                                mangaId = remote.mangaId,
                                chapterId = remote.chapterId,
                                pageIndex = remote.pageIndex,
                                content = remote.content,
                                createdAt = Instant.fromEpochMilliseconds(remote.createdAt),
                                updatedAt = Instant.fromEpochMilliseconds(remote.updatedAt),
                            ),
                            emitSyncEvent = false
                        )
                    }
                }
            }
        }

        val categoryRepo = mangaCategoryRepository
        if (categoryRepo != null) {
            for (remote in fetchedMangaCategories) {
                if (remote.deviceId == deviceId) continue
                if (remote.isDeleted) {
                    runCatching { categoryRepo.delete(remote.id, emitSyncEvent = false) }
                    continue
                }
                val local = runCatching { categoryRepo.get(remote.id) }.getOrNull()
                if (local != null && local.updatedAt.toEpochMilliseconds() >= remote.updatedAt) continue
                runCatching {
                    categoryRepo.applyRemote(
                        com.folio.reader.manga.MangaCategory(
                            id = remote.id,
                            name = remote.name,
                            sortOrder = remote.sortOrder,
                            updatedAt = Instant.fromEpochMilliseconds(remote.updatedAt),
                        ),
                        remote.mangaIds.toSet(),
                    )
                }
            }
        }
    }

    private suspend fun resolveLocalBookId(remoteBookId: String): String {
        val directLocal = runCatching { bookRepository.getBook(remoteBookId) }.getOrNull()
        if (directLocal != null) return directLocal.id

        val remoteBook = fetchedBooks.find { it.id == remoteBookId }
        if (remoteBook != null) {
            if (remoteBook.epubHash.isNotBlank()) {
                val localByHash = runCatching { bookRepository.getBookByEpubHash(remoteBook.epubHash) }.getOrNull()
                if (localByHash != null) return localByHash.id
            }
            if (!remoteBook.isbn.isNullOrBlank()) {
                val localByIsbn = runCatching { bookRepository.getBookByIsbn(remoteBook.isbn) }.getOrNull()
                if (localByIsbn != null) return localByIsbn.id
            }
        }
        return remoteBookId
    }

    private suspend fun applyRemoteBooks() {
        for (remote in fetchedBooks) {
            val targetId = resolveLocalBookId(remote.id)
            val local = runCatching { bookRepository.getBook(targetId) }.getOrNull()
            if (remote.isDeleted) {
                // Another device deleted the book. Drop the local row too; the EPUB
                // file stays until the reader removes it explicitly. No sync event —
                // the tombstone already came from the cloud.
                if (local != null && remote.deviceId != deviceId) {
                    runCatching { bookRepository.deleteBook(local.id, emitSyncEvent = false) }
                }
                continue
            }
            if (remote.deviceId == deviceId) continue
            val remoteUpdated = remote.updatedAt
            val localUpdated = local?.updatedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE
            when {
                // Metadata alone cannot be read. Keep cloud-only books out of
                // this device's library unless a matching local EPUB exists.
                local == null -> Unit
                localUpdated < remoteUpdated -> {
                    val mergedBook = remote.toBook().copy(
                        id = local.id,
                        coverPath = local.coverPath ?: remote.coverPath
                    )
                    runCatching { bookRepository.updateBook(mergedBook, emitSyncEvent = false) }
                }

                else -> Unit
            }
        }
    }

    private suspend fun applyRemotePositions() {
        val affectedBookIds = mutableSetOf<String>()
        for (remote in fetchedPositions) {
            if (remote.deviceId == deviceId) continue
            val targetBookId = resolveLocalBookId(remote.bookId)
            if (bookRepository.getBook(targetBookId) == null) continue
            val local = positionRepository.getPosition(targetBookId, remote.deviceId)
            if (local == null || local.updatedAt.toEpochMilliseconds() < remote.updatedAt) {
                positionRepository.upsertPosition(
                    ReadingPosition(
                        bookId = targetBookId,
                        deviceId = remote.deviceId,
                        chapterId = remote.chapterId,
                        spineIndex = remote.spineIndex,
                        contentLocator = remote.contentLocator,
                        characterOffset = remote.characterOffset,
                        paragraphIndex = remote.paragraphIndex,
                        normalizedProgress = remote.normalizedProgress,
                        chapterProgress = remote.chapterProgress,
                        scrollOffset = remote.scrollOffset,
                        updatedAt = Instant.fromEpochMilliseconds(remote.updatedAt)
                    ),
                    emitSyncEvent = false
                )
                affectedBookIds.add(targetBookId)
            }
        }
        for (bookId in affectedBookIds) {
            val latest = positionRepository.getLatestPositionAcrossDevices(bookId)
            if (latest != null) {
                bookRepository.updateNormalizedProgress(bookId, latest.normalizedProgress)
            }
        }
    }

    private suspend fun applyRemoteAnnotations() {
        // Deliberately no `remote.deviceId == deviceId` skip: an annotation carries the
        // id of the device that created it, so an edit or delete made elsewhere lands on
        // a document naming this device and would be discarded. updatedAt alone gives
        // last-writer-wins, and re-applying this device's own unchanged document is a
        // no-op.
        for (remote in fetchedHighlights) {
            if (bookRepository.getBook(resolveLocalBookId(remote.bookId)) == null) continue
            val local = highlightRepository.getHighlight(remote.id)
            when {
                remote.isDeleted -> if (local != null && !local.isDeleted) highlightRepository.deleteHighlight(
                    local.id,
                    emitSyncEvent = false
                )

                local == null || local.updatedAt.toEpochMilliseconds() < remote.updatedAt ->
                    highlightRepository.updateHighlight(remote.toHighlight(), emitSyncEvent = false)
            }
        }

        for (remote in fetchedNotes) {
            if (bookRepository.getBook(resolveLocalBookId(remote.bookId)) == null) continue
            val local = noteRepository.getNote(remote.id)
            when {
                remote.isDeleted -> if (local != null && !local.isDeleted) noteRepository.deleteNote(
                    local.id,
                    emitSyncEvent = false
                )

                local == null || local.updatedAt.toEpochMilliseconds() < remote.updatedAt ->
                    noteRepository.updateNote(remote.toNote(), emitSyncEvent = false)
            }
        }

        for (remote in fetchedBookmarks) {
            if (bookRepository.getBook(resolveLocalBookId(remote.bookId)) == null) continue
            val local = bookmarkRepository.getBookmark(remote.id)
            when {
                remote.isDeleted -> if (local != null && !local.isDeleted) bookmarkRepository.deleteBookmark(
                    local.id,
                    emitSyncEvent = false
                )

                local == null || local.updatedAt.toEpochMilliseconds() < remote.updatedAt ->
                    bookmarkRepository.updateBookmark(remote.toBookmark(), emitSyncEvent = false)
            }
        }
    }

    private suspend fun applyRemoteSessions() {
        val measuredFrom = ReadingSession.FIRST_MEASURED_SESSION.toEpochMilliseconds()
        for (remote in fetchedSessions) {
            if (remote.deviceId == deviceId || remote.isActive) continue
            // Another device's pre-fix history is just as unmeasured as this one's was.
            if (remote.startedAt < measuredFrom) continue
            val targetBookId = resolveLocalBookId(remote.bookId)
            if (bookRepository.getBook(targetBookId) == null) continue
            val known = sessionRepository.getSessionsForBook(targetBookId).first().firstOrNull { it.id == remote.id }
            if (known != null) continue
            runCatching {
                sessionRepository.insertSession(
                    ReadingSession(
                        id = remote.id,
                        bookId = targetBookId,
                        cycleId = remote.cycleId,
                        deviceId = remote.deviceId,
                        startedAt = Instant.fromEpochMilliseconds(remote.startedAt),
                        endedAt = remote.endedAt?.let { Instant.fromEpochMilliseconds(it) },
                        durationMs = remote.durationMs,
                        startPosition = Json.Default.decodeFromString(
                            ReadingPosition.serializer(),
                            remote.startPosition
                        ),
                        endPosition = remote.endPosition?.let {
                            Json.Default.decodeFromString(ReadingPosition.serializer(), it)
                        },
                        startProgress = remote.startProgress,
                        endProgress = remote.endProgress,
                        wordsRead = remote.wordsRead,
                        isActive = remote.isActive
                    ),
                    emitSyncEvent = false
                )
            }
        }
    }

    // Enqueue methods for local changes
    fun enqueueBookChange(book: Book, operation: SyncOperation) {
        scope.launch {
            syncRepository.enqueueSync(
                "book",
                book.id,
                operation,
                Json.Default.encodeToString(Book.serializer(), book)
            )
        }
    }

    fun enqueuePositionChange(position: ReadingPosition, operation: SyncOperation) {
        scope.launch {
            syncRepository.enqueueSync(
                "position",
                "${position.bookId}_${position.deviceId}",
                operation,
                Json.Default.encodeToString(ReadingPosition.serializer(), position)
            )
        }
    }

    fun enqueueHighlightChange(highlight: Highlight, operation: SyncOperation) {
        scope.launch {
            syncRepository.enqueueSync(
                "highlight",
                highlight.id,
                operation,
                Json.Default.encodeToString(Highlight.serializer(), highlight)
            )
        }
    }

    fun enqueueNoteChange(note: Note, operation: SyncOperation) {
        scope.launch {
            syncRepository.enqueueSync(
                "note",
                note.id,
                operation,
                Json.Default.encodeToString(Note.serializer(), note)
            )
        }
    }

    fun enqueueBookmarkChange(bookmark: Bookmark, operation: SyncOperation) {
        scope.launch {
            syncRepository.enqueueSync(
                "bookmark",
                bookmark.id,
                operation,
                Json.Default.encodeToString(Bookmark.serializer(), bookmark)
            )
        }
    }

    fun enqueueSessionChange(session: ReadingSession, operation: SyncOperation) {
        scope.launch {
            syncRepository.enqueueSync(
                "session",
                session.id,
                operation,
                Json.Default.encodeToString(ReadingSession.serializer(), session)
            )
        }
    }

    fun enqueueSettingsChange(settings: ReaderSettings, operation: SyncOperation) {
        scope.launch {
            syncRepository.enqueueSync(
                "settings",
                "global",
                operation,
                Json.Default.encodeToString(ReaderSettings.serializer(), settings)
            )
        }
    }

    /**
     * Returns whether the cloud API is connected and reachable with valid credentials.
     */
    fun isApiConnected(): Boolean {
        return firestoreSync.isConnected()
    }

    /**
     * Background sync triggered on application open.
     * Executes sync only if API connectivity is verified.
     */
    fun syncOnAppOpen() {
        scope.launch {
            if (isApiConnected()) {
                syncOnce()
            }
        }
    }

    /**
     * Background sync triggered on application close/exit. Fires a bounded final
     * sync on the engine scope without blocking the caller — exit runs on the UI
     * thread, and a 5 s network stall there froze rotation and window close. The
     * outbox is durable: anything not flushed before process death is pushed on
     * the next launch.
     */
    fun syncOnAppClose() {
        if (!isApiConnected()) return
        scope.launch {
            runCatching {
                withTimeoutOrNull(EXIT_SYNC_BUDGET_MS) {
                    syncOnce()
                }
            }
        }
    }
}

// Placeholder interfaces for Firebase sync
interface FirestoreSync {
    fun authenticate()
    fun isConnected(): Boolean
    fun upsertBook(book: FsBook)
    fun upsertPosition(position: FsReadingPosition)
    fun upsertHighlight(highlight: FsHighlight)
    fun upsertNote(note: FsNote)
    fun upsertBookmark(bookmark: FsBookmark)
    fun upsertSession(session: FsReadingSession)
    fun upsertSettings(settings: FsSettings)
    fun upsertCollection(collection: FsCollection)
    fun upsertSeries(series: FsSeries)
    fun upsertTag(tag: FsTag)
    fun upsertQuote(quote: FsQuote)
    fun upsertRevisitItem(item: FsRevisitItem)
    fun fetchBooks(): List<FsBook>
    fun fetchPositions(): List<FsReadingPosition>
    fun fetchHighlights(): List<FsHighlight>
    fun fetchNotes(): List<FsNote>
    fun fetchBookmarks(): List<FsBookmark>
    fun fetchSessions(sinceStartedAtMs: Long = 0L): List<FsReadingSession>
    fun fetchCollections(): List<FsCollection>
    fun fetchSeries(): List<FsSeries>
    fun fetchTags(): List<FsTag>
    fun fetchQuotes(): List<FsQuote>
    fun fetchRevisitItems(): List<FsRevisitItem>
    fun fetchSettings(): FsSettings?
    fun upsertManga(manga: FsManga)
    fun upsertMangaChapter(chapter: FsMangaChapter)
    fun upsertMangaNote(note: FsMangaNote)
    fun upsertMangaCategory(category: FsMangaCategory)
    fun fetchManga(): List<FsManga>
    fun fetchMangaChapters(): List<FsMangaChapter>
    fun fetchMangaNotes(): List<FsMangaNote>
    fun fetchMangaCategories(): List<FsMangaCategory>
}

interface StorageSync {
    val uid: String
    suspend fun uploadBook(uid: String, bookId: String, localPath: String, onProgress: ((Float) -> Unit)? = null)
    suspend fun downloadBook(
        uid: String,
        bookId: String,
        destinationPath: String,
        onProgress: ((Float) -> Unit)? = null
    )

    suspend fun uploadCover(uid: String, bookId: String, localPath: String, onProgress: ((Float) -> Unit)? = null)
    suspend fun deleteBook(uid: String, bookId: String)
}
