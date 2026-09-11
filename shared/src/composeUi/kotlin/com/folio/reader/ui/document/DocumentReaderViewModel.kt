package com.folio.reader.ui.document

import com.folio.reader.database.DocumentRepository
import com.folio.reader.model.DocumentBookmark
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.DocumentLocator
import com.folio.reader.model.DocumentPosition
import com.folio.reader.platform.FolioFileSystem
import java.io.File
import java.io.IOException
import java.util.UUID
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
import kotlinx.datetime.Clock

class DocumentReaderViewModel(
    private val repository: DocumentRepository,
    private val fileSystem: FolioFileSystem,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(DocumentReaderState())
    val state: StateFlow<DocumentReaderState> = _state.asStateFlow()

    private var documentId: String? = null
    private var bookmarkJob: Job? = null
    private var persistJob: Job? = null

    fun open(documentId: String) {
        this.documentId = documentId
        bookmarkJob?.cancel()
        persistJob?.cancel()
        _state.value = DocumentReaderState()
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
                else -> loadReflowable(documentId, saved)
            }
            runCatching { repository.markOpened(documentId) }
            collectBookmarks(documentId)
        }
    }

    private suspend fun loadReflowable(documentId: String, saved: DocumentPosition?) {
        val index = File(fileSystem.getDocumentGeneratedIndexPath(documentId))
        if (!withContext(ioDispatcher) { index.isFile }) {
            fail(DocumentReaderErrorKind.MISSING_FILE, "The generated document content is missing")
            return
        }
        val html = try {
            withContext(ioDispatcher) { index.readText(Charsets.UTF_8) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail(DocumentReaderErrorKind.CORRUPT, "The generated document content could not be read")
            return
        }
        val progress = saved?.normalizedProgress?.coerceIn(0.0, 1.0) ?: 0.0
        val locator = (saved?.locator as? DocumentLocator.Reflowable) ?: reflowableLocator()
        _state.value = _state.value.copy(
            normalizedProgress = progress,
            reflowableLocator = locator,
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
            updateReflowableProgress(clamped)
        }
    }

    fun updateReflowablePage(currentPage: Int, pageCount: Int) {
        _state.value = _state.value.copy(
            currentPage = currentPage.coerceIn(1, pageCount.coerceAtLeast(1)),
            pageCount = pageCount.coerceAtLeast(1)
        )
    }

    fun setMode(mode: DocumentReaderMode) {
        _state.value = _state.value.copy(mode = mode)
    }

    fun rotateClockwise() {
        _state.value = _state.value.copy(rotationDegrees = (_state.value.rotationDegrees + 90) % 360)
    }

    fun setControlsVisible(visible: Boolean) {
        _state.value = _state.value.copy(controlsVisible = visible)
    }

    fun updateReflowableProgress(progress: Float, characterOffset: Int = 0) {
        val clamped = progress.coerceIn(0f, 1f).toDouble()
        val locator = reflowableLocator(characterOffset)
        _state.value = _state.value.copy(normalizedProgress = clamped, reflowableLocator = locator)
        schedulePersist(DocumentPosition(requireDocumentId(), locator, clamped))
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

    override fun close() {
        currentPosition()?.let { position ->
            kotlinx.coroutines.runBlocking(ioDispatcher) { runCatching { repository.upsertPosition(position) } }
        }
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
