package com.folio.reader.ui.reader

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingPositionRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The reader's place in the open book: restored on open, tracked while the
 * reader moves, written to disk coalesced, and synced on close.
 */
internal class ReaderPositionStore(
    private val positionRepository: ReadingPositionRepository,
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val tracker: ReadingSessionTracker,
    private val positionState: MutableStateFlow<ReadingPosition?>,
    private val sessionState: MutableStateFlow<ReadingSession?>,
    private val bookState: MutableStateFlow<Book?>,
    private val chaptersState: MutableStateFlow<List<Chapter>>,
    private val currentChapterIndexState: MutableStateFlow<Int>,
    private val currentBookId: () -> String?,
    private val syncEngine: SyncEngine?
) {
    private var progressPersistJob: Job? = null

    /** Words preceding each chapter, keyed on the chapter list instance. */
    private var wordPrefixSource: List<Chapter>? = null
    private var wordPrefixStarts = LongArray(0)

    /**
     * Restores the last known position across devices (preferring most recently
     * updated), falling back to this device. Returns the unclamped start chapter
     * index; the session tracker still needs it for its fallback position.
     */
    suspend fun restorePosition(
        bookId: String,
        deviceId: String,
        chapters: List<Chapter>,
        startChapterOverride: Int?,
        book: Book?
    ): Int {
        val saved = positionRepository.getLatestPositionAcrossDevices(bookId)
            ?: positionRepository.getPosition(bookId, deviceId)
        val startIndex = startChapterOverride
            ?: saved?.let { pos ->
                chapters.indexOfFirst { it.spineIndex == pos.spineIndex }.takeIf { it >= 0 }
            }
            ?: 0
        currentChapterIndexState.value = startIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))

        // Initialize position for this device using saved position data. The
        // saved scroll offset only applies when the saved position is actually
        // inside the chapter being opened (search jumps / overrides may target
        // a different chapter).
        val startChapter = chapters.getOrNull(currentChapterIndexState.value)
        val savedForStartChapter = saved?.takeIf { startChapter != null && it.spineIndex == startChapter.spineIndex }
        positionState.value = savedForStartChapter?.copy(deviceId = deviceId) ?: ReadingPosition(
            bookId = bookId,
            deviceId = deviceId,
            chapterId = startChapter?.id ?: "",
            spineIndex = startChapter?.spineIndex ?: 0,
            contentLocator = "",
            normalizedProgress = saved?.normalizedProgress ?: 0.0,
            chapterProgress = 0.0,
            scrollOffset = 0.0
        )
        println("📚 Position initialized: ${if (saved != null) "restored from DB" else "created new"}, chapter=${positionState.value?.chapterProgress}, book=${positionState.value?.normalizedProgress}")

        // Update book's normalized progress from saved position
        saved?.let { pos ->
            book?.updateProgress(pos.normalizedProgress)
            bookRepository.updateNormalizedProgress(bookId, pos.normalizedProgress)
            bookState.value = book
        }
        return startIndex
    }

    fun updateScrollProgress(scrollFraction: Float, characterOffsetEstimate: Int = 0) {
        val current = positionState.value ?: return
        tracker.markReadingActivity()
        val chapters = chaptersState.value
        val chapter = chapters.getOrNull(currentChapterIndexState.value)
        val totalWords = bookState.value?.totalWords ?: 0L

        // Chapter progress from scroll fraction
        val chapterProgress = scrollFraction.toDouble().coerceIn(0.0, 1.0)

        // Normalized book progress via cumulative word counts across chapters
        val normalized = if (chapters.isNotEmpty() && totalWords > 0) {
            val chapterWords = chapter?.wordCount ?: 0L
            ((wordsBefore(currentChapterIndexState.value) + chapterWords * chapterProgress).toDouble() / totalWords)
                .coerceIn(0.0, 1.0)
        } else {
            current.normalizedProgress
        }

        val updated = current.withProgress(
            newNormalizedProgress = normalized,
            newChapterProgress = chapterProgress,
            newScrollOffset = scrollFraction.toDouble()
        )
        // In-memory only: this is what the progress bar reads, and it has to stay
        // live. Everything expensive below is coalesced.
        positionState.value = updated

        scheduleProgressPersist(updated, normalized)
    }

    /**
     * Cumulative words before each chapter, computed once whenever the chapter list
     * changes. Summing per report was re-walking hundreds of chapters on every scroll
     * tick, which is what made scrolling feel jumpy.
     */
    private fun wordsBefore(index: Int): Long {
        val chapters = chaptersState.value
        if (wordPrefixSource !== chapters || wordPrefixStarts.size != chapters.size) {
            val starts = LongArray(chapters.size)
            var acc = 0L
            for (i in chapters.indices) {
                starts[i] = acc
                acc += chapters[i].wordCount
            }
            wordPrefixStarts = starts
            wordPrefixSource = chapters
        }
        return wordPrefixStarts.getOrElse(index) { 0L }
    }

    /** Debounced so a fast scroll costs one write, not one per frame. */
    private fun scheduleProgressPersist(position: ReadingPosition, normalized: Double) {
        progressPersistJob?.cancel()
        progressPersistJob = scope.launch {
            kotlinx.coroutines.delay(PROGRESS_PERSIST_DELAY_MS)
            persistProgress(position, normalized)
        }
    }

    /** Writes the reader's place on disk; flushed on chapter changes and when closing. */
    private fun persistProgress(position: ReadingPosition, normalized: Double) {
        val totalMs = tracker.activeMs()
        val words = tracker.creditedWordsFor(normalized, totalMs)
        sessionState.value = sessionState.value?.copy(
            endPosition = position,
            endProgress = normalized,
            durationMs = totalMs,
            wordsRead = words
        )
        scope.launch {
            runCatching {
                positionRepository.upsertPosition(position)
                currentBookId()?.let { bookId ->
                    bookRepository.updateNormalizedProgress(bookId, normalized)
                    bookState.value?.updateProgress(normalized)
                }
            }
        }
    }

    /** Persists the current place now, e.g. before leaving a chapter. */
    fun flushProgress() {
        progressPersistJob?.cancel()
        progressPersistJob = null
        val position = positionState.value ?: return
        persistProgress(position, position.normalizedProgress)
    }

    fun updatePosition(newPosition: ReadingPosition) {
        positionState.value = newPosition
        scope.launch {
            runCatching { positionRepository.upsertPosition(newPosition) }
        }
    }

    /** Writes the reader's place at close time and queues it for cloud sync. */
    suspend fun persistOnClose(bookId: String, position: ReadingPosition) {
        positionRepository.upsertPosition(position)
        bookRepository.updateNormalizedProgress(bookId, position.normalizedProgress)

        // Enqueue position for cloud sync and trigger sync on book close
        syncEngine?.let { engine ->
            val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
            if (settings?.cloudSyncEnabled == true && settings.syncPositions) {
                engine.enqueuePositionChange(position, com.folio.reader.sync.SyncOperation.UPSERT)
                engine.triggerSync(immediate = true)
                println("☁️ Book closed: Reading position queued and cloud sync triggered")
            }
        }
    }

    private companion object {
        /** Coalesces scroll-driven progress writes into one save per pause. */
        const val PROGRESS_PERSIST_DELAY_MS = 1200L
    }
}
