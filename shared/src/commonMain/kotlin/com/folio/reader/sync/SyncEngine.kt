package com.folio.reader.sync

import com.folio.reader.database.*
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.firebase.FsBook
import com.folio.reader.firebase.FsBookmark
import com.folio.reader.firebase.FsCollection
import com.folio.reader.firebase.FsHighlight
import com.folio.reader.firebase.FsNote
import com.folio.reader.firebase.FsQuote
import com.folio.reader.firebase.FsReadingPosition
import com.folio.reader.firebase.FsReadingSession
import com.folio.reader.firebase.FsRevisitItem
import com.folio.reader.firebase.FsSeries
import com.folio.reader.firebase.FsSettings
import com.folio.reader.firebase.FsTag
import com.folio.reader.firebase.FirestorePaths
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val deviceId: String
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
     * Local edits always enter the outbox, but only explicit lifecycle/manual
     * events start a sync. This prevents scroll-position writes from making a
     * network request every few seconds; the periodic loop handles them later.
     */
    fun triggerSync(immediate: Boolean = false) {
        if (!immediate) return
        debounceJob?.cancel()
        scope.launch { syncOnce() }
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
            // current transport cannot reconstruct such an entity (and book
            // deletes have no Firestore operation), so drop these stale entries.
            // Current soft-deleted annotations include their entity payload.
            if (item.operation == SyncOperation.DELETE && item.payload == "{}") {
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
        // Always sync book metadata; EPUB file body upload is handled separately via uploadBookToCloud (opt-in via syncEpubs setting)
        val book = Json.Default.decodeFromString(Book.serializer(), item.payload)
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
        val fsHighlight = FsHighlight.fromHighlight(highlight)
        firestoreSync.upsertHighlight(fsHighlight)
    }

    private suspend fun pushNote(item: SyncQueueItem) {
        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        if (settings?.syncAnnotations == false) return

        val note = Json.Default.decodeFromString(Note.serializer(), item.payload)
        val fsNote = FsNote.fromNote(note)
        firestoreSync.upsertNote(fsNote)
    }

    private suspend fun pushBookmark(item: SyncQueueItem) {
        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
        if (settings?.syncAnnotations == false) return

        val bookmark = Json.Default.decodeFromString(Bookmark.serializer(), item.payload)
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
        val settings = Json.Default.decodeFromString(ReaderSettings.serializer(), item.payload)
        val fsSettings = FsSettings(
            userId = "", // From auth
            global = Json.Default.encodeToString(settings),
            bookSettings = emptyMap(),
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            deviceId = deviceId
        )
        firestoreSync.upsertSettings(fsSettings)
    }

    private suspend fun pushTag(item: SyncQueueItem) {
        val tag = Json.Default.decodeFromString(Tag.serializer(), item.payload)
        firestoreSync.upsertTag(FsTag(tag.id, tag.name, tag.color, tag.createdAt.toEpochMilliseconds(), deviceId))
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
                deviceId = deviceId
            )
        )
    }

    private suspend fun pushSeries(item: SyncQueueItem) {
        val series = Json.Default.decodeFromString(Series.serializer(), item.payload)
        firestoreSync.upsertSeries(FsSeries(series.id, series.name, series.sortOrder, deviceId))
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

    private suspend fun fetchRemoteChanges() {
        fetchedBooks = firestoreSync.fetchBooks(deviceId)
        fetchedPositions = firestoreSync.fetchPositions(deviceId)
        fetchedHighlights = firestoreSync.fetchHighlights(deviceId)
        fetchedNotes = firestoreSync.fetchNotes(deviceId)
        fetchedBookmarks = firestoreSync.fetchBookmarks(deviceId)
        fetchedSessions = firestoreSync.fetchSessions(deviceId)
        fetchedCollections = firestoreSync.fetchCollections(deviceId)
        fetchedSeries = firestoreSync.fetchSeries(deviceId)
        fetchedTags = firestoreSync.fetchTags(deviceId)
        fetchedQuotes = firestoreSync.fetchQuotes(deviceId)
        fetchedRevisitItems = firestoreSync.fetchRevisitItems(deviceId)

        _syncState.update {
            it.copy(
                pendingDownloadCount = fetchedBooks.size + fetchedPositions.size + fetchedHighlights.size +
                        fetchedNotes.size + fetchedBookmarks.size + fetchedSessions.size +
                        fetchedCollections.size + fetchedSeries.size + fetchedTags.size +
                        fetchedQuotes.size + fetchedRevisitItems.size
            )
        }
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
        runCatching {
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
        runCatching {
            settingsRepository.setRaw("annotations_backfilled_at", Clock.System.now().toString())
        }
        if (enqueued > 0) println("☁️ Annotation backfill queued $enqueued item(s) for cloud sync")
    }

    private suspend fun applyRemoteChanges() {
        applyRemoteBooks()
        applyRemotePositions()
        applyRemoteAnnotations()
        applyRemoteSessions()
        applySecondaryEntities()

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
    }

    /**
     * Secondary entity types (collections, series, tags, quotes, revisit items)
     * carry no updatedAt watermark; they merge insert-if-missing by id so the
     * union of all devices converges. Revisit items additionally adopt a remote
     * resolvedAt when the local copy is still unresolved.
     */
    private suspend fun applySecondaryEntities() {
        val localCollectionIds = collectionRepository.getAllCollections().first().map { it.id }.toSet()
        for (remote in fetchedCollections) {
            if (remote.deviceId == deviceId || remote.id in localCollectionIds) continue
            runCatching {
                collectionRepository.insertCollection(
                    FolioCollection(
                        id = remote.id,
                        name = remote.name,
                        color = remote.color,
                        sortOrder = remote.sortOrder,
                        createdAt = Instant.fromEpochMilliseconds(remote.createdAt)
                    ),
                    emitSyncEvent = false
                )
            }
        }

        for (remote in fetchedSeries) {
            if (remote.deviceId == deviceId) continue
            if (runCatching { seriesRepository.getSeries(remote.id) }.getOrNull() != null) continue
            runCatching {
                seriesRepository.insertSeries(
                    Series(id = remote.id, name = remote.name, sortOrder = remote.sortOrder),
                    emitSyncEvent = false
                )
            }
        }

        val localTagIds = tagRepository.getAllTags().first().map { it.id }.toSet()
        for (remote in fetchedTags) {
            if (remote.deviceId == deviceId || remote.id in localTagIds) continue
            runCatching {
                tagRepository.insertTag(
                    Tag(
                        id = remote.id,
                        name = remote.name,
                        color = remote.color,
                        createdAt = Instant.fromEpochMilliseconds(remote.createdAt)
                    ),
                    emitSyncEvent = false
                )
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
            if (remote.deviceId == deviceId) continue
            val targetId = resolveLocalBookId(remote.id)
            val local = runCatching { bookRepository.getBook(targetId) }.getOrNull()
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

    private suspend fun bookRepoInsert(remote: FsBook) =
        bookRepository.insertBook(remote.toBook(), emitSyncEvent = false)

    private suspend fun bookRepoUpdate(remote: FsBook) =
        runCatching { bookRepository.updateBook(remote.toBook()) }
            .onFailure { runCatching { bookRepository.insertBook(remote.toBook()) } }

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
     * Background sync triggered on application close/exit.
     * Executes sync only if API connectivity is verified.
     */
    fun syncOnAppClose() {
        if (isApiConnected()) {
            runBlocking {
                runCatching {
                    withTimeoutOrNull(5000L) {
                        syncOnce()
                    }
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
    fun fetchBooks(excludeDeviceId: String): List<FsBook>
    fun fetchPositions(excludeDeviceId: String): List<FsReadingPosition>
    fun fetchHighlights(excludeDeviceId: String): List<FsHighlight>
    fun fetchNotes(excludeDeviceId: String): List<FsNote>
    fun fetchBookmarks(excludeDeviceId: String): List<FsBookmark>
    fun fetchSessions(excludeDeviceId: String): List<FsReadingSession>
    fun fetchSettings(excludeDeviceId: String): FsSettings?
    fun fetchCollections(excludeDeviceId: String): List<FsCollection>
    fun fetchSeries(excludeDeviceId: String): List<FsSeries>
    fun fetchTags(excludeDeviceId: String): List<FsTag>
    fun fetchQuotes(excludeDeviceId: String): List<FsQuote>
    fun fetchRevisitItems(excludeDeviceId: String): List<FsRevisitItem>
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
