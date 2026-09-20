package com.folio.reader.importer

import com.folio.reader.database.BookRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.ReadingPositionRepository
import com.folio.reader.epub.EpubParser
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.FormattingMode
import com.folio.reader.platform.FolioPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.File
import java.util.UUID

class BookImporter(
    private val platform: FolioPlatform,
    private val epubParser: EpubParser,
    private val bookRepository: BookRepository,
    private val positionRepository: ReadingPositionRepository,
    private val searchIndexer: SearchIndexer,
    private val hashUtil: com.folio.reader.platform.FileHasher,
    /**
     * Optional: gives every imported (or re-linked) book its default collection
     * so it lands on a real shelf — the manga library's Main-category rule.
     * Null keeps imports exactly as they were (tests).
     */
    private val collectionRepository: CollectionRepository? = null,
    /**
     * Optional semantic indexer (ML_PLAN Phase 3). Null keeps imports byte-for-byte as
     * they were, which is what the existing tests rely on, and is what the app falls back
     * to when the embedding model has not been downloaded.
     */
    private val embeddingIndexer: com.folio.reader.ml.EmbeddingIndexer? = null,
) {
    suspend fun importEpub(filePath: String): Result<Book> {
        return withContext(Dispatchers.IO) {
            try {
                println("Import: Starting import of $filePath")

                // 1. Calculate hash
                println("Import: Calculating file hash...")
                val hash = hashUtil.sha256File(filePath)

                // 2. Deterministic book ID based on file content hash so identical books across devices get identical IDs
                val deterministicBookId = UUID.nameUUIDFromBytes("folio_epub_$hash".toByteArray()).toString()

                // 3. Check for duplicates or existing remote-synced book record
                println("Import: Checking for duplicates...")
                val existingByHash = bookRepository.getBookByEpubHash(hash)
                if (existingByHash != null) {
                    val fileExists = File(platform.fileSystem.getBookEpubPath(existingByHash.id)).exists()
                    if (fileExists) {
                        println("Import: Duplicate detected (same file hash)")
                        runCatching { collectionRepository?.ensureMembership(existingByHash.id) }
                        return@withContext Result.failure(DuplicateBookException("Book already imported (same file)", existingByHash))
                    }
                    println("Import: Linking local EPUB file to existing synced metadata record (${existingByHash.id})")
                }

                // 4. Parse EPUB
                println("Import: Parsing EPUB...")
                val parsed = epubParser.parseEpub(filePath)
                println("Import: Parsed ${parsed.chapters.size} chapters, ${parsed.totalWords} words")

                // 5. Check for metadata duplicates (ISBN) if no hash match was found
                if (existingByHash == null && parsed.metadata.isbn != null) {
                    val existingByIsbn = bookRepository.getBookByIsbn(parsed.metadata.isbn!!)
                    if (existingByIsbn != null) {
                        val fileExists = File(platform.fileSystem.getBookEpubPath(existingByIsbn.id)).exists()
                        if (fileExists) {
                            println("Import: Duplicate detected (same ISBN)")
                            runCatching { collectionRepository?.ensureMembership(existingByIsbn.id) }
                            return@withContext Result.failure(DuplicateBookException("Book with same ISBN exists", existingByIsbn))
                        }
                    }
                }

                // 6. Book ID
                val bookId = existingByHash?.id ?: deterministicBookId
                println("Import: Using book ID: $bookId")

                // 6. Copy to library
                println("Import: Copying file to library...")
                val sourceFile = File(filePath)
                val epubPath = platform.fileSystem.copyToLibrary(sourceFile, bookId)

                // 7. Extract cover
                println("Import: Extracting cover...")
                val coverPath = if (parsed.coverData != null) {
                    val coverFile = File(platform.fileSystem.getBookDir(bookId), "cover.jpg")
                    coverFile.writeBytes(parsed.coverData!!)
                    coverFile.absolutePath
                } else null

                // 8. Create book record
                println("Import: Creating book record...")
                val book = Book(
                    id = bookId,
                    title = usableTitle(parsed.metadata.title, sourceFile),
                    subtitle = parsed.metadata.subtitle,
                    authors = parsed.metadata.authors,
                    publisher = parsed.metadata.publisher,
                    language = parsed.metadata.language,
                    isbn = parsed.metadata.isbn,
                    description = parsed.metadata.description,
                    publicationDate = parsed.metadata.publicationDate,
                    coverPath = coverPath,
                    epubHash = hash,
                    epubFileSize = sourceFile.length(),
                    addedAt = Clock.System.now(),
                    totalCharacters = parsed.totalCharacters,
                    totalWords = parsed.totalWords,
                    chapterCount = parsed.chapters.size,
                    formattingMode = parsed.chapters.firstOrNull()?.let { _ -> com.folio.reader.model.FormattingMode.HYBRID } ?: com.folio.reader.model.FormattingMode.HYBRID
                )

                // 9. Update chapters with book ID
                val chaptersWithBookId = parsed.chapters.map { it.copy(bookId = bookId) }

                // 10. Save to database
                println("Import: Saving to database...")
                bookRepository.insertBook(book)
                bookRepository.insertChapters(bookId, chaptersWithBookId)

                // A fresh import always lands on a real shelf — Main unless the
                // reader moves it (the manga library's ensureMembership rule).
                runCatching { collectionRepository?.ensureMembership(bookId) }

                // 11. Index for search — one bulk transaction (150-chapter books
                // were doing 150 separate commits, hanging the import for minutes).
                println("Import: Building search index (${chaptersWithBookId.size} chapters)...")
                val indexEntries = chaptersWithBookId.mapNotNull { chapter ->
                    val html = parsed.rawHtmlByHref[chapter.href] ?: ""
                    val text = com.folio.reader.importer.SearchIndexer.extractPlainText(html)
                    if (text.isBlank()) null
                    else com.folio.reader.database.ChapterIndexEntry(chapter.id, chapter.spineIndex, chapter.title, text)
                }
                
                // Run indexing in a separate coroutine scope to avoid blocking
                println("Import: Indexing ${indexEntries.size} chapters in bulk...")
                searchIndexer.indexChaptersBulk(bookId, indexEntries)
                println("Import: Search indexing complete")

                // Semantic indexing rides the import path, which is already off the UI
                // thread, so it adds nothing to the read or scroll path. A no-op when the
                // embedding model has not been downloaded.
                embeddingIndexer?.let { indexer ->
                    // Invalidate a stale index before adding to it. The chunk window is derived
                    // from the model and can change on an app upgrade without the model id
                    // changing; when it does, the previously stored vectors describe different
                    // spans of text than the chunker now produces. Reconciling here is what covers
                    // the desktop app, which has no background backfill worker and builds its index
                    // solely through this path. Idempotent and cheap when the recipe is unchanged.
                    runCatching { indexer.reconcileRecipe() }
                        .onSuccess { reason -> if (reason != null) println("Import: cleared stale index: $reason") }
                        .onFailure { println("Import: could not reconcile chunk recipe; indexing anyway: ${it.message}") }
                    println("Import: Embedding ${indexEntries.size} chapters...")
                    val result = runCatching { indexer.indexChapters(bookId, indexEntries) }
                    result.onFailure { it.printStackTrace() }
                    println("Import: Semantic indexing -> ${result.getOrNull()}")
                }

                println("Import: Successfully imported '${book.title}'")
                return@withContext Result.success(book)
            } catch (e: Exception) {
                println("Import: FAILED - ${e.message}")
                e.printStackTrace()
                return@withContext Result.failure(e)
            }
        }
    }

    suspend fun importMultiple(filePaths: List<String>): List<Result<Book>> {
        return coroutineScope {
            filePaths.map { path -> async { importEpub(path) } }.awaitAll()
        }
    }
}

class DuplicateBookException(
    message: String,
    val existingBook: Book
) : Exception(message)

class SearchIndexer(
    private val searchRepository: com.folio.reader.database.SearchRepository
) {
    suspend fun indexChapter(bookId: String, chapterId: String, spineIndex: Int, title: String, html: String) {
        val text = extractPlainText(html)
        searchRepository.indexChapter(bookId, chapterId, spineIndex, title, text)
    }

    suspend fun indexChaptersBulk(bookId: String, entries: List<com.folio.reader.database.ChapterIndexEntry>) {
        searchRepository.indexChaptersBulk(bookId, entries)
    }

    companion object {
        /**
         * Plain text from chapter HTML: tags stripped, entities decoded, whitespace collapsed.
         *
         * ### Why the entity pass is not optional
         *
         * Tag-stripping with a regex leaves character references exactly as written, so
         * `It&#8217;s` reached the FTS5 index and the UI as the literal nine characters
         * `&#8217;`. That is visible in search snippets and it is worse than cosmetic: the
         * index holds `&#8217;` as its own token, so a query for `it's` cannot match text
         * that was stored as `It&#8217;s`, and the reader sees the reference spelled out in
         * the result.
         *
         * Decoding happens **after** tags are removed, never before. Decoding first would
         * turn a chapter that discusses literal markup — a coding book writing `&lt;div&gt;`
         * — into real tags for the stripper to eat, deleting the text the reader was meant to
         * search. In this order, `&lt;div&gt;` survives as `<div>` in the plain text, which is
         * what the reader searched for and what the snippet should show.
         *
         * `jsoup`'s parser is used rather than a hand-written reference table: it covers the
         * full HTML5 named set plus numeric references in both bases, which is the same set the
         * browser rendering this chapter will honour. `Entities.unescape` alone is not enough —
         * it handles named references but leaves a bare `&` and unknown sequences untouched in
         * ways that differ from the parser.
         *
         * ### Why whitespace is normalised to ASCII space, not just `\s`
         *
         * Decoding is what *creates* the problem this guards against: `&nbsp;` becomes
         * U+00A0, and `&thinsp;` U+2009, `&ensp;` U+2002 and so on. Kotlin's `\s` does not
         * match those, so a bare `.replace(Regex("\\s+"), " ")` leaves them in place — and
         * they are not merely untidy:
         *
         * - `TextChunker` counts words with `split(" ")`, i.e. on **ASCII space only**, so an
         *   NBSP-joined phrase counts as one word.
         * - The SQL mirror of that count uses `replace(content, ' ', '')`, which is likewise
         *   ASCII-only.
         *
         * Two implementations of the same predicate that agree on ordinary prose can disagree
         * on a `&nbsp;`-laden title page, and a disagreement between "how many words are
         * there" and "how many chunks can exist" is exactly what made a backfill unable to
         * finish. Normalising every Unicode space to U+0020 first keeps `\s`, `split(" ")` and
         * the SQL all measuring the same thing.
         */
        fun extractPlainText(html: String): String {
            val withoutTags = html
                .replace(Regex("(?s)<script.*?</script>"), "")
                .replace(Regex("(?s)<style.*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
            return org.jsoup.parser.Parser.unescapeEntities(withoutTags, false)
                // Every Unicode space separator -> U+0020, so the collapse below and the
                // word-count predicate downstream both see the same characters.
                .replace(UNICODE_SPACES, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Unicode space separators that `\s` misses.
         *
         * Listed explicitly rather than using `Char.isWhitespace()` so the set is fixed and
         * reviewable: `isWhitespace` is broader than anything HTML produces and would fold
         * characters the reader may have meant literally. These are the ones the HTML5 entity
         * set can actually generate.
         */
        private val UNICODE_SPACES = Regex("[\u00A0\u1680\u2000-\u200A\u202F\u205F\u3000]")
    }

    private fun extractText(html: String): String = extractPlainText(html)

    suspend fun reindexBook(bookId: String, chapters: List<Chapter>, htmlByHref: Map<String, String>) {
        searchRepository.deleteIndexForBook(bookId)
        val entries = chapters.mapNotNull { chapter ->
            val html = htmlByHref[chapter.href] ?: ""
            val text = extractPlainText(html)
            if (text.isBlank()) null
            else com.folio.reader.database.ChapterIndexEntry(chapter.id, chapter.spineIndex, chapter.title, text)
        }
        searchRepository.indexChaptersBulk(bookId, entries)
    }
}

/**
 * The book's title, or the source file's name when the EPUB's own metadata is not a title.
 *
 * `<dc:title>` is trusted everywhere else in this codebase and it is right to trust it — it is the
 * publisher's own statement of the book's name, and no heuristic beats it. But the element is
 * *optional*, and files run through conversion tools occasionally carry something that is not a
 * name at all. One book in the 22-book test library declares
 *
 * ```
 * file:///C|/Documents%20and%20Settings/Tony/My%20Documents/...
 * ```
 *
 * — an absolute path, percent-encoded, with the pipe that a Windows drive colon becomes in a URL.
 * Rendered in a search result it reads as a bug in the app rather than in the file, which is how it
 * surfaced: the first semantic-search run showed it as the headline of a result card.
 *
 * The test is deliberately narrow — an explicit URI scheme, or a Windows drive path — rather than
 * "does this look like prose". A false positive silently replaces a real title with a filename,
 * which is worse than the disease; a false negative leaves a path on screen, which is merely ugly.
 * `%20` alone is not a trigger for the same reason: a title may legitimately contain a percent sign.
 */
internal fun usableTitle(title: String, source: File): String {
    val looksLikeUri = title.startsWith("file:", ignoreCase = true) ||
        title.startsWith("http://", ignoreCase = true) ||
        title.startsWith("https://", ignoreCase = true)
    val looksLikeDrivePath = WINDOWS_ABSOLUTE_PATH.matches(title)
    if (!looksLikeUri && !looksLikeDrivePath) return title
    return source.nameWithoutExtension.ifBlank { title }
}

/** `C:\dir\file.epub` or `C:/dir/file.epub` — an absolute Windows path, not a title. */
private val WINDOWS_ABSOLUTE_PATH = Regex("""^[A-Za-z]:[\\/].*""")