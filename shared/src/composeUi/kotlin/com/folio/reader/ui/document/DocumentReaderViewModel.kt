package com.folio.reader.ui.document

import com.folio.reader.database.DocumentRepository
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentBookmark
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.DocumentLocator
import com.folio.reader.model.DocumentPosition
import com.folio.reader.platform.FolioFileSystem
import com.folio.reader.ui.render.READER_WINDOW_MAX_SECTIONS
import com.folio.reader.ui.render.READER_WINDOW_PRELOAD
import com.folio.reader.ui.render.ReaderSection
import com.folio.reader.ui.render.WindowOp
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

class DocumentReaderViewModel(
    private val repository: DocumentRepository,
    private val fileSystem: FolioFileSystem,
    /**
     * The persisted document-reader default (ReaderSettings.documentReaderMode).
     * Honored on every open — the mode used to be per-visit memory, silently
     * reset to single-page each time, which is what "the reader defaults don't
     * contain the doc defaults" was.
     */
    initialMode: DocumentReaderMode = DocumentReaderMode.SINGLE_PAGE,
    /** Persisted on every mode change, so the choice outlives the visit. */
    private val onModeChanged: suspend (DocumentReaderMode) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // Public so a dispose site can launch [close] on a scope that outlives the
    // composition (mirrors MangaReaderViewModel.scope); [close] cancels it last.
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(DocumentReaderState(mode = initialMode))
    val state: StateFlow<DocumentReaderState> = _state.asStateFlow()

    private var documentId: String? = null
    private var bookmarkJob: Job? = null
    private var persistJob: Job? = null

    // ── Reflowable windowing state (continuous layout) ───────────────────────
    // Mirrors ReaderContentLoader/ReaderViewModel for EPUB, but every chunk is
    // already in memory so there is no async chapter load: an extension only
    // emits a one-shot WindowOp and waits for the surface to apply it.
    /** All ordered chunks of the reflowable document; empty until one is loaded. */
    private var reflowableChunks: List<ReaderSection> = emptyList()
    /** Chunk indices currently on screen. spineIndex == chunk index by construction. */
    private var windowRange: IntRange = IntRange.EMPTY
    /** The live sections on screen including DOM extensions (bounds/dedupe checks). */
    private var liveWindow: List<ReaderSection> = emptyList()
    /** The chunk holding the viewport centre; drives section-local→global progress. */
    private var currentChunkIndex: Int = 0
    private var windowNonce: Long = 0L
    private val windowOpAck = MutableStateFlow(0L)
    /** Synchronous single-flight guard so one edge event cannot race in a duplicate. */
    private val extending = AtomicBoolean(false)

    fun open(documentId: String) {
        this.documentId = documentId
        bookmarkJob?.cancel()
        persistJob?.cancel()
        // A fresh state that keeps the mode: the document changes, the reader's
        // own defaults do not.
        _state.value = DocumentReaderState(mode = _state.value.mode)
        scope.launch {
            val document = try {
                repository.getDocument(documentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(DocumentReaderErrorKind.IO, error.message ?: "Could not load the document")
                return@launch
            } ?: run {
                fail(DocumentReaderErrorKind.NOT_FOUND, "This document is no longer in the library")
                return@launch
            }
            _state.value = _state.value.copy(document = document)

            val saved = try {
                repository.observePosition(documentId).first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            val path = document.localPath
            if (path.isNullOrBlank() || !withContext(ioDispatcher) { File(path).isFile }) {
                fail(DocumentReaderErrorKind.MISSING_FILE, "The document file is missing")
                return@launch
            }

            when (document.format) {
                DocumentFormat.PDF -> {
                    val savedPage = (saved?.locator as? DocumentLocator.FixedPage)?.pageIndex ?: 0
                    _state.value = _state.value.copy(
                        currentPage = savedPage.coerceAtLeast(0),
                        normalizedProgress = saved?.normalizedProgress?.coerceIn(0.0, 1.0)
                            ?: document.normalizedProgress.coerceIn(0.0, 1.0),
                        loadState = DocumentReaderLoadState.Ready(DocumentReaderContent.Pdf(path))
                    )
                }
                else -> loadReflowable(document, saved)
            }
            runCatching { repository.markOpened(documentId) }
            collectBookmarks(documentId)
        }
    }

    private suspend fun loadReflowable(document: Document, saved: DocumentPosition?) {
        val documentId = document.id
        val index = File(fileSystem.getDocumentGeneratedIndexPath(documentId))
        val indexBytes = withContext(ioDispatcher) { if (index.isFile) index.length() else -1L }
        if (indexBytes < 0) {
            fail(DocumentReaderErrorKind.MISSING_FILE, "The generated document content is missing")
            return
        }
        val key = ReflowableContentKey(documentId, document.contentHash, indexBytes)
        val cached = reflowableContentCache[key]
        val content = cached ?: run {
            val html = try {
                withContext(ioDispatcher) { index.readText(Charsets.UTF_8) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(DocumentReaderErrorKind.CORRUPT, "The generated document content could not be read")
                return
            }
            val chunks = withContext(ioDispatcher) { DocumentChunker.chunk(html) }
            ReflowableContent(html, chunks).also { reflowableContentCache.put(key, it) }
        }
        val html = content.html
        val chunks = content.chunks
        val progress = saved?.normalizedProgress?.coerceIn(0.0, 1.0) ?: 0.0
        val locator = (saved?.locator as? DocumentLocator.Reflowable) ?: reflowableLocator()

        // Chunks are windowed like EPUB chapters, so a large/image-heavy doc never
        // overruns the WebView tile budget. The single-section Reflowable content is kept
        // for the paged-mode fallback and desktop; the screen renders windowed sections
        // whenever [reflowableWindow] is populated and layout is scroll.
        reflowableChunks = chunks
        // Map the saved global progress to an anchor chunk plus a section-local
        // offset within it. This global→local mapping is approximate (chunks are
        // uneven), which the windowing contract accepts.
        val anchorIndex = (progress * chunks.size).toInt().coerceIn(0, chunks.lastIndex)
        val localOffset = (progress * chunks.size - anchorIndex).toFloat().coerceIn(0f, 1f)
        val from = (anchorIndex - READER_WINDOW_PRELOAD).coerceAtLeast(0)
        val to = (anchorIndex + READER_WINDOW_PRELOAD).coerceAtMost(chunks.lastIndex)
        windowRange = from..to
        liveWindow = chunks.subList(from, to + 1).toList()
        currentChunkIndex = anchorIndex
        windowNonce = 0L
        windowOpAck.value = 0L
        _state.value = _state.value.copy(
            normalizedProgress = progress,
            reflowableLocator = locator,
            reflowableWindow = liveWindow,
            reflowableAnchorChapterId = "document-chunk-$anchorIndex",
            reflowableWindowOp = null,
            reflowableScrollOffset = localOffset,
            loadState = DocumentReaderLoadState.Ready(
                DocumentReaderContent.Reflowable(html, "generated/index.html", progress.toFloat())
            )
        )
    }

    private fun collectBookmarks(documentId: String) {
        bookmarkJob = scope.launch {
            repository.observeBookmarks(documentId).collect { bookmarks ->
                _state.value = _state.value.copy(bookmarks = bookmarks)
            }
        }
    }

    fun onPdfOpened(pageCount: Int) {
        if (pageCount <= 0) {
            fail(DocumentReaderErrorKind.CORRUPT, "The PDF has no readable pages")
            return
        }
        val page = _state.value.currentPage.coerceIn(0, pageCount - 1)
        _state.value = _state.value.copy(
            pageCount = pageCount,
            currentPage = page,
            normalizedProgress = pageProgress(page, pageCount)
        )
        persistFixedPage()
    }

    fun setCurrentPage(pageIndex: Int) {
        val count = _state.value.pageCount
        val page = if (count > 0) pageIndex.coerceIn(0, count - 1) else pageIndex.coerceAtLeast(0)
        _state.value = _state.value.copy(
            currentPage = page,
            normalizedProgress = pageProgress(page, count)
        )
        persistFixedPage()
    }

    fun seekToProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (_state.value.document?.format == DocumentFormat.PDF) {
            val count = _state.value.pageCount
            if (count > 0) setCurrentPage((clamped * (count - 1)).roundToInt())
        } else {
            // The bottom-bar fraction is whole-document; store it directly rather
            // than through the section-local conversion. The surface's own seek is
            // section-local (an engine limitation), and the next progress report
            // re-derives the global value from the visible chunk.
            updateReflowableGlobalProgress(clamped)
        }
    }

    fun updateReflowablePage(currentPage: Int, pageCount: Int) {
        _state.value = _state.value.copy(
            currentPage = currentPage.coerceIn(1, pageCount.coerceAtLeast(1)),
            pageCount = pageCount.coerceAtLeast(1)
        )
    }

    fun setMode(mode: DocumentReaderMode) {
        if (mode == _state.value.mode) return
        _state.value = _state.value.copy(mode = mode)
        scope.launch { onModeChanged(mode) }
    }

    fun rotateClockwise() {
        _state.value = _state.value.copy(rotationDegrees = (_state.value.rotationDegrees + 90) % 360)
    }

    fun setControlsVisible(visible: Boolean) {
        _state.value = _state.value.copy(controlsVisible = visible)
    }

    fun updateReflowableProgress(progress: Float, characterOffset: Int = 0) {
        // In windowed mode the surface reports progress WITHIN the visible chunk,
        // so convert the section-local fraction to a whole-document fraction using
        // the current chunk index. With a single chunk (paged fallback / degenerate
        // window) this is the identity, so the whole-document semantics are kept.
        val local = progress.coerceIn(0f, 1f)
        val chunkCount = reflowableChunks.size.coerceAtLeast(1)
        val global = ((currentChunkIndex + local) / chunkCount).coerceIn(0f, 1f)
        updateReflowableGlobalProgress(global, characterOffset)
    }

    /**
     * Records a whole-document reflowable progress fraction and schedules its
     * persist. Used directly by the paged/single-section fallback surface (which
     * reports whole-document progress) and by [seekToProgress]; the windowed
     * surface routes section-local progress through [updateReflowableProgress].
     */
    fun updateReflowableGlobalProgress(progress: Float, characterOffset: Int = 0) {
        val clamped = progress.coerceIn(0f, 1f).toDouble()
        val locator = reflowableLocator(characterOffset)
        _state.value = _state.value.copy(normalizedProgress = clamped, reflowableLocator = locator)
        schedulePersist(DocumentPosition(requireDocumentId(), locator, clamped))
    }

    /**
     * The reflowable engine hit a window edge: grow the window that way. Mirrors
     * ReaderViewModel.extendWindow — a synchronous single-flight guard is flipped
     * before any suspend so a second edge event that fires while the first is
     * still running is dropped rather than racing in a duplicate section.
     */
    fun extendReflowableWindow(forward: Boolean) {
        if (extending.getAndSet(true)) return
        if (hasPendingReflowableWindowOp()) { extending.set(false); return }
        scope.launch {
            try {
                var added = false
                for (i in 0 until READER_WINDOW_PRELOAD) {
                    if (!extendReflowableOne(forward)) break
                    added = true
                }
                // Once for the whole batch: a trim between two appends could be
                // conflated away with one of them.
                if (added) trimReflowableWindow(forward)
            } finally {
                extending.set(false)
            }
        }
    }

    /** Adds the next chunk in [forward] direction. False when nothing is left to add. */
    private suspend fun extendReflowableOne(forward: Boolean): Boolean {
        val chunks = reflowableChunks
        val range = windowRange
        if (chunks.isEmpty() || range == IntRange.EMPTY) return false
        val nextIndex = if (forward) range.last + 1 else range.first - 1
        if (forward && nextIndex > chunks.lastIndex) return false
        if (!forward && nextIndex < 0) return false
        val section = chunks.getOrNull(nextIndex) ?: return false
        // Never emit an op for a chunk already in the live window.
        if (liveWindow.any { it.spineIndex == section.spineIndex }) return false
        windowNonce++
        val nonce = windowNonce
        windowRange = if (forward) range.first..nextIndex else nextIndex..range.last
        liveWindow = if (forward) liveWindow + section else listOf(section) + liveWindow
        _state.value = _state.value.copy(
            reflowableWindowOp = if (forward) WindowOp.Append(section, nonce) else WindowOp.Prepend(section, nonce)
        )
        // Wait for the surface to apply this op before the next one overwrites it.
        withTimeoutOrNull(2500) { windowOpAck.first { it >= nonce } }
        return true
    }

    /** Drops the far end once the live window passes READER_WINDOW_MAX_SECTIONS. */
    private fun trimReflowableWindow(forward: Boolean) {
        var range = windowRange
        val before = range
        while (range.last - range.first + 1 > READER_WINDOW_MAX_SECTIONS) {
            range = if (forward) (range.first + 1)..range.last else range.first..(range.last - 1)
        }
        if (range == before) return
        windowRange = range
        // spineIndex == chunk index, so the kept range IS the kept spine range.
        liveWindow = liveWindow.filter { it.spineIndex in range }
        windowNonce++
        _state.value = _state.value.copy(
            reflowableWindowOp = WindowOp.Trim(fromSpine = range.first, toSpine = range.last, nonce = windowNonce)
        )
    }

    /** Surfaces acknowledge each applied op; unacknowledged ops block further extension. */
    fun onReflowableWindowOpApplied(nonce: Long) {
        if (nonce > windowOpAck.value) windowOpAck.value = nonce
        // The op is one-shot: clear it once the surface has consumed it so a later
        // WebView recreation cannot replay a stale mutation onto a fresh document.
        if ((_state.value.reflowableWindowOp?.nonce ?: 0L) <= nonce) {
            _state.value = _state.value.copy(reflowableWindowOp = null)
        }
    }

    /** True when an extension op is still waiting to be applied on screen. */
    private fun hasPendingReflowableWindowOp(): Boolean =
        (_state.value.reflowableWindowOp?.nonce ?: 0L) > windowOpAck.value

    /**
     * The visible chunk moved with the scroll: retarget the chunk that drives the
     * whole-document progress conversion, without reloading anything.
     */
    fun onReflowableVisibleSection(spineIndex: Int) {
        val chunks = reflowableChunks
        if (chunks.isEmpty() || spineIndex < 0 || spineIndex > chunks.lastIndex) return
        currentChunkIndex = spineIndex
    }

    fun toggleBookmark() {
        val id = documentId ?: return
        scope.launch {
            val current = currentLocator() ?: return@launch
            val existing = _state.value.bookmarks.firstOrNull { samePlace(it.locator, current) }
            if (existing != null) {
                repository.deleteBookmark(existing.id)
            } else {
                repository.upsertBookmark(
                    DocumentBookmark(
                        id = UUID.randomUUID().toString(),
                        documentId = id,
                        locator = current,
                        label = when (current) {
                            is DocumentLocator.FixedPage -> "Page ${current.pageIndex + 1}"
                            is DocumentLocator.Reflowable -> null
                        }
                    )
                )
            }
        }
    }

    fun removeBookmark(bookmarkId: String) {
        scope.launch { repository.deleteBookmark(bookmarkId) }
    }

    fun reportError(error: DocumentReaderError) {
        fail(error.kind, error.message)
    }

    fun resolveResource(source: String): String? {
        val id = documentId ?: return null
        val generated = fileSystem.getDocumentGeneratedDir(id)
        val relative = source.substringBefore('#').substringBefore('?')
            .removePrefix("generated/").removePrefix("./").replace('/', File.separatorChar)
        val candidate = runCatching { File(generated, relative).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { generated.canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it.isFile && it.toPath().startsWith(root.toPath()) }?.absolutePath
    }

    fun flush() {
        currentPosition()?.let { position -> scope.launch { repository.upsertPosition(position) } }
    }

    /**
     * Persists the final reader position on the way out, then tears the scope
     * down. This used to wrap the write in runBlocking(ioDispatcher), but it is
     * invoked from onDispose on the UI thread, where the write can stall for
     * hundreds of ms behind the global DB write mutex (a backfill or sync drain
     * holds it), long enough to drop frames and trip the 5s ANR watchdog. It is
     * now suspend: the dispose site launches it on [scope], which outlives the
     * composition, so the position is still persisted without blocking the UI.
     * The repository switches to IO internally, exactly as flush() relies on.
     */
    suspend fun close() {
        currentPosition()?.let { position -> runCatching { repository.upsertPosition(position) } }
        scope.cancel()
    }

    private fun persistFixedPage() {
        val count = _state.value.pageCount
        if (count <= 0) return
        val page = _state.value.currentPage.coerceIn(0, count - 1)
        schedulePersist(DocumentPosition(requireDocumentId(), DocumentLocator.FixedPage(pageIndex = page), pageProgress(page, count)))
    }

    private fun schedulePersist(position: DocumentPosition) {
        persistJob?.cancel()
        persistJob = scope.launch {
            kotlinx.coroutines.delay(250)
            repository.upsertPosition(position)
        }
    }

    private fun currentPosition(): DocumentPosition? {
        val id = documentId ?: return null
        val locator = currentLocator() ?: return null
        return DocumentPosition(id, locator, _state.value.normalizedProgress.coerceIn(0.0, 1.0))
    }

    private fun currentLocator(): DocumentLocator? = when (_state.value.loadState) {
        is DocumentReaderLoadState.Ready -> when (_state.value.document?.format) {
            DocumentFormat.PDF -> DocumentLocator.FixedPage(pageIndex = _state.value.currentPage)
            null -> null
            else -> _state.value.reflowableLocator ?: reflowableLocator()
        }
        else -> null
    }

    private fun requireDocumentId(): String = checkNotNull(documentId)

    private fun fail(kind: DocumentReaderErrorKind, message: String) {
        _state.value = _state.value.copy(loadState = DocumentReaderLoadState.Error(DocumentReaderError(kind, message)))
    }

    companion object {
        fun pageProgress(pageIndex: Int, pageCount: Int): Double = when {
            pageCount <= 1 -> if (pageCount == 1) 1.0 else 0.0
            else -> pageIndex.coerceIn(0, pageCount - 1).toDouble() / (pageCount - 1).toDouble()
        }

        private fun samePlace(first: DocumentLocator, second: DocumentLocator): Boolean = when {
            first is DocumentLocator.FixedPage && second is DocumentLocator.FixedPage -> first.pageIndex == second.pageIndex
            first is DocumentLocator.Reflowable && second is DocumentLocator.Reflowable ->
                first.sectionId == second.sectionId && first.characterOffset == second.characterOffset
            else -> false
        }
    }
}
