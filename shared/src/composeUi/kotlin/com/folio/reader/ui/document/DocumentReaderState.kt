package com.folio.reader.ui.document

import androidx.compose.ui.graphics.ImageBitmap
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentBookmark
import com.folio.reader.model.DocumentLocator

enum class DocumentReaderMode { SINGLE_PAGE, CONTINUOUS }

enum class DocumentReaderErrorKind {
    NOT_FOUND,
    MISSING_FILE,
    CORRUPT,
    ENCRYPTED,
    RENDER,
    IO
}

data class DocumentReaderError(
    val kind: DocumentReaderErrorKind,
    val message: String
)

sealed interface DocumentReaderContent {
    data class Pdf(val path: String) : DocumentReaderContent

    data class Reflowable(
        val html: String,
        val chapterHref: String,
        val initialProgress: Float
    ) : DocumentReaderContent
}

sealed interface DocumentReaderLoadState {
    data object Loading : DocumentReaderLoadState
    data class Ready(val content: DocumentReaderContent) : DocumentReaderLoadState
    data class Error(val error: DocumentReaderError) : DocumentReaderLoadState
}

data class DocumentReaderState(
    val document: Document? = null,
    val loadState: DocumentReaderLoadState = DocumentReaderLoadState.Loading,
    val bookmarks: List<DocumentBookmark> = emptyList(),
    val mode: DocumentReaderMode = DocumentReaderMode.SINGLE_PAGE,
    val currentPage: Int = 0,
    val pageCount: Int = 0,
    val rotationDegrees: Int = 0,
    val normalizedProgress: Double = 0.0,
    val reflowableLocator: DocumentLocator.Reflowable? = null,
    val controlsVisible: Boolean = true
) {
    val isCurrentPositionBookmarked: Boolean
        get() = bookmarks.any { bookmark ->
            when (val locator = bookmark.locator) {
                is DocumentLocator.FixedPage -> locator.pageIndex == currentPage
                is DocumentLocator.Reflowable ->
                    reflowableLocator?.let { it.sectionId == locator.sectionId && it.characterOffset == locator.characterOffset } == true
            }
        }
}

data class FixedPageRenderRequest(
    val pageIndex: Int,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val rotationDegrees: Int = 0,
    val thumbnail: Boolean = false
) {
    val normalizedRotation: Int = ((rotationDegrees % 360) + 360) % 360
}

interface FixedPageDocument : AutoCloseable {
    val pageCount: Int
    suspend fun render(request: FixedPageRenderRequest): ImageBitmap
}

class FixedPageException(
    val kind: DocumentReaderErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

expect suspend fun openFixedPageDocument(path: String): FixedPageDocument
