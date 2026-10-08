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

internal fun reflowableLocator(characterOffset: Int = 0): DocumentLocator.Reflowable =
    DocumentLocator.Reflowable(
        sectionId = DOCUMENT_REFLOWABLE_SECTION,
        characterOffset = characterOffset.coerceAtLeast(0)
    )
