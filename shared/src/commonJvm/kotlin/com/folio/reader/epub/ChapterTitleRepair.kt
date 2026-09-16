package com.folio.reader.epub

import com.folio.reader.database.BookRepository
import com.folio.reader.model.Chapter

/**
 * Chapter titles are derived once at import and then stored, so a book added before a labeling
 * fix keeps its old labels forever. Both shapes of stale label are repaired here: the original
 * case where the book title was stored as every chapter name, and the front-matter case where a
 * non-prose page was numbered from its spine position and reads as "Chapter 10" sitting between
 * "Acknowledgments" and "Dramatis Personae".
 */
internal fun List<Chapter>.needsTitleRepair(): Boolean {
    if (size <= 2) return false
    return distinctBy { it.title }.size == 1 || any { it.isNumberedFurniture() }
}

/** A "Chapter N" title on a page too short to be prose is a spine position, not a chapter. */
private fun Chapter.isNumberedFurniture(): Boolean =
    title.matches(spinePositionNumber) && wordCount < FrontMatterLabels.MIN_BODY_WORDS

private val spinePositionNumber = Regex("Chapter \\d+")

/**
 * Re-derives stored chapter titles for one book, and returns without touching the database when
 * nothing needs repairing or when the fresh parse cannot be trusted.
 *
 * Only `chapters` rows are rewritten, and only while ids and spine order are unchanged: reading
 * positions, bookmarks and highlights key on them, so a repair must never renumber the book.
 */
suspend fun repairStoredChapterTitles(
    bookId: String,
    epubPath: String,
    parser: EpubParser,
    repository: BookRepository
) {
    runCatching {
        val existing = repository.getChaptersForBook(bookId)
        if (!existing.needsTitleRepair()) return

        val fixed = parser.parseEpub(epubPath).chapters.map { it.copy(bookId = bookId) }
        if (fixed.size != existing.size) return
        if (fixed.map { it.id } != existing.map { it.id }) return
        if (fixed.map { it.spineIndex } != existing.map { it.spineIndex }) return
        if (fixed.map { it.title } == existing.map { it.title }) return

        repository.insertChapters(bookId, fixed)
    }
}
