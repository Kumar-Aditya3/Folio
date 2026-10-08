package com.folio.reader.epub

import com.folio.reader.database.BookRepository
import com.folio.reader.database.SettingsRepository
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
    if (distinctBy { it.title }.size == 1) return true
    if (any { it.isNumberedFurniture() }) return true
    // Old spine-position numbering mixed generated "Chapter N" (N = file index) in with the
    // book's real chapter titles, so the auto numbers ran out of order ("Chapter 1",
    // "Chapter 13", "Chapter 2"). Content-based numbering is monotonic down the spine, so any
    // decrease in the bare "Chapter N" sequence means the stored labels predate the fix.
    val numbers = mapNotNull { chapterNumberOf(it.title) }
    if (numbers.zipWithNext().any { (a, b) -> b < a }) return true
    return false
}

/** A "Chapter N" title on a page too short to be prose is a spine position, not a chapter. */
private fun Chapter.isNumberedFurniture(): Boolean =
    title.matches(spinePositionNumber) && wordCount < FrontMatterLabels.MIN_BODY_WORDS

private val spinePositionNumber = Regex("Chapter \\d+")
private val chapterNumberExact = Regex("^Chapter (\\d+)$")
private fun chapterNumberOf(title: String): Int? =
    chapterNumberExact.matchEntire(title.trim())?.groupValues?.get(1)?.toIntOrNull()

/**
 * Bump when chapter labelling rules change, so every book is re-checked once against the new
 * ones. Stored per book under [titlesCheckedKey] because the check below is not always
 * convergent: a book whose own navigation supplies bare "Chapter N" labels reproduces the very
 * titles `needsTitleRepair` objects to, so re-deriving them changes nothing and the next open
 * would pay for the whole-book parse all over again.
 */
const val TITLE_LABELER_VERSION = "1"

internal fun titlesCheckedKey(bookId: String) = "chapterTitlesChecked.$bookId"

/**
 * Re-derives stored chapter titles for one book, at most once per [TITLE_LABELER_VERSION], and
 * returns without touching the database when nothing needs repairing or when the fresh parse
 * cannot be trusted.
 *
 * Only `chapters` rows are rewritten, and only while ids and spine order are unchanged: reading
 * positions, bookmarks and highlights key on them, so a repair must never renumber the book.
 */
suspend fun repairStoredChapterTitles(
    bookId: String,
    epubPath: String,
    parser: EpubParser,
    repository: BookRepository,
    settings: SettingsRepository
) {
    runCatching {
        if (settings.getRaw(titlesCheckedKey(bookId)) == TITLE_LABELER_VERSION) return
        val existing = repository.getChaptersForBook(bookId)
        if (existing.needsTitleRepair()) {
            val fixed = parser.parseEpub(epubPath).chapters.map { it.copy(bookId = bookId) }
            // Every guard the repair used to bail on individually, in one condition: the fresh
            // labels may only replace the stored ones when they describe the same chapters.
            val canApply = fixed.size == existing.size &&
                fixed.map { it.id } == existing.map { it.id } &&
                fixed.map { it.spineIndex } == existing.map { it.spineIndex } &&
                fixed.map { it.title } != existing.map { it.title }
            if (canApply) repository.insertChapters(bookId, fixed)
        }
        // Reached by every pass that got an answer, including "nothing to do". A parse or write
        // that threw lands in runCatching instead, so that book is checked again next open.
        settings.setRaw(titlesCheckedKey(bookId), TITLE_LABELER_VERSION)
    }
}
