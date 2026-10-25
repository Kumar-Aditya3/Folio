package com.folio.reader.ui.document

import com.folio.reader.model.DocumentLocator
import com.folio.reader.model.ReadingPosition

internal const val DOCUMENT_REFLOWABLE_SECTION = "document"

internal fun DocumentLocator.Reflowable.toSurfacePosition(
    documentId: String,
    progress: Double
): ReadingPosition = ReadingPosition(
    bookId = documentId,
    deviceId = "document-reader",
    chapterId = sectionId,
    spineIndex = 0,
    contentLocator = domLocator.orEmpty(),
    characterOffset = characterOffset,
    normalizedProgress = progress.coerceIn(0.0, 1.0),
    chapterProgress = progress.coerceIn(0.0, 1.0),
    scrollOffset = progress.coerceIn(0.0, 1.0)
)

internal fun reflowableLocator(
    characterOffset: Int = 0,
    sectionId: String = DOCUMENT_REFLOWABLE_SECTION,
    progress: Double? = null,
): DocumentLocator.Reflowable =
    DocumentLocator.Reflowable(
        sectionId = sectionId,
        // The existing paragraph/fraction seek grammar also gives bookmarks a scroll fallback.
        domLocator = progress?.coerceIn(0.0, 1.0)?.let { "p::$it" },
        characterOffset = characterOffset.coerceAtLeast(0)
    )
